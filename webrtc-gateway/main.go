package main

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
)

const (
	magic                     = "VDH1"
	typeFormat                = 1
	typeFrame                 = 2
	flagConfig                = 1
	flagKeyFrame              = 2
	defaultH264ProfileLevelID = "42e032" // Baseline, Level 5.0
)
const browserHTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>VirtualDisplay WebRTC</title>
<style>
html,body{margin:0;width:100%;height:100%;background:#111;color:#ddd;font-family:system-ui,sans-serif;overflow:hidden}
body{display:flex;flex-direction:column}
#bar{height:38px;display:flex;align-items:center;gap:12px;padding:0 12px;background:#202020;box-sizing:border-box;font-size:13px}
#status{color:#9ad}
button{background:#333;color:#ddd;border:1px solid #555;border-radius:4px;padding:5px 10px;cursor:pointer}
#wrap{position:relative;flex:1;display:flex;align-items:center;justify-content:center;min-height:0;overflow:hidden}
video{max-width:100%;max-height:100%;width:auto;height:auto;background:#000;object-fit:contain}
#cursor{position:absolute;width:0;height:0;pointer-events:none;z-index:20;display:none;filter:drop-shadow(0 1px 1px rgba(0,0,0,.9))}
#cursor::before{content:"";position:absolute;left:0;top:0;width:0;height:0;border-top:15px solid #fff;border-right:8px solid transparent;transform:rotate(-8deg)}
#cursor::after{content:"";position:absolute;left:2px;top:3px;width:0;height:0;border-top:10px solid #111;border-right:5px solid transparent;transform:rotate(-8deg)}
</style>
</head>
<body>
<div id="bar"><span>VirtualDisplay WebRTC</span><span id="status">connecting...</span><button id="reconnect">Reconnect</button></div>
<div id="wrap"><video id="video" autoplay playsinline muted></video><div id="cursor"></div></div>
<script>
const video=document.getElementById('video');
const wrap=document.getElementById('wrap');
const cursor=document.getElementById('cursor');
const status=document.getElementById('status');
let pc=null;
function setStatus(s){ status.textContent=s; console.log('[WebRTC]',s); }
function updateCursor(c){
  if(!c || !c.visible || !c.width || !c.height || !video.videoWidth || !video.videoHeight){cursor.style.display='none';return;}
  const vr=video.getBoundingClientRect();
  const wr=wrap.getBoundingClientRect();
  if(vr.width<=0 || vr.height<=0){cursor.style.display='none';return;}
  // c.x/c.y are in the 1920x1080 virtual display. Map them to the actual
  // displayed video rectangle, including object-fit/letterboxing.
  const px=vr.left-wr.left+(c.x/c.width)*vr.width;
  const py=vr.top-wr.top+(c.y/c.height)*vr.height;
  cursor.style.left=px+'px';
  cursor.style.top=py+'px';
  cursor.style.display='block';
}
async function pollCursor(){
  try{const r=await fetch('/cursor',{cache:'no-store'});if(r.ok)updateCursor(await r.json());}
  catch(e){}
  requestAnimationFrame(()=>setTimeout(pollCursor,33));
}
async function start(){
  if(pc){try{pc.close()}catch(e){}pc=null;}
  setStatus('creating peer...');
  pc=new RTCPeerConnection({iceServers:[]});
  pc.addTransceiver('video',{direction:'recvonly'});
  pc.ontrack=e=>{video.srcObject=(e.streams&&e.streams[0])?e.streams[0]:new MediaStream([e.track]);video.play().catch(()=>{});};
  pc.oniceconnectionstatechange=()=>setStatus('ICE: '+pc.iceConnectionState);
  pc.onconnectionstatechange=()=>setStatus('PC: '+pc.connectionState);
  try{
    const offer=await pc.createOffer({offerToReceiveVideo:true});
    await pc.setLocalDescription(offer);
    await new Promise(resolve=>{if(pc.iceGatheringState==='complete')return resolve();const timer=setTimeout(resolve,5000);pc.onicegatheringstatechange=()=>{if(pc.iceGatheringState==='complete'){clearTimeout(timer);resolve();}}});
    const res=await fetch('/offer',{method:'POST',headers:{'Content-Type':'application/sdp'},body:pc.localDescription.sdp});
    if(!res.ok)throw new Error('HTTP '+res.status+' '+await res.text());
    await pc.setRemoteDescription({type:'answer',sdp:await res.text()});
    setStatus('connected');
  }catch(e){console.error('[WebRTC]',e);setStatus('error: '+e.message);if(pc){try{pc.close()}catch(_){}pc=null;}}
}
window.addEventListener('resize',()=>{fetch('/cursor',{cache:'no-store'}).then(r=>r.ok?r.json():null).then(updateCursor).catch(()=>{});});
document.getElementById('reconnect').onclick=start;
start();
pollCursor();
</script>
</body>
</html>`

type streamHeader struct{ Width, Height int }
type h264Frame struct {
	seq   uint64
	ptsUs int64
	flags int
	data  []byte // normalized Annex-B access unit
}

type h264Source struct {
	addr           string
	controlAddr    string
	mu             sync.RWMutex
	header         streamHeader
	config         []byte
	sps            []byte
	pps            []byte
	profileLevelID string
	latest         *h264Frame
	latestKey      *h264Frame
	seq            uint64
	changed        chan struct{}
	keyRequest     uint64

	// Diagnostics / stream state.
	connected      bool
	connectCount   uint64
	frames         uint64
	keyFrames      uint64
	configFrames   uint64
	bytes          uint64
	lastFrameAt    time.Time
	lastFrameSize  int
	lastError      string
	pliCount       uint64
	lastPLIAt      time.Time
	framesSent     uint64
	keyFramesSent  uint64
	configSent     uint64
	waitedNoConfig uint64
	cursor         cursorState
}

func main() {
	android := getenv("ANDROID_H264", "127.0.0.1:18080")
	control := getenv("ANDROID_CONTROL", "127.0.0.1:18081")
	listen := getenv("LISTEN", ":19000")

	h264 := newH264Source(android, control)
	go h264.run()
	go h264.pollCursor()

	mux := http.NewServeMux()
	mux.HandleFunc("/offer", func(w http.ResponseWriter, r *http.Request) { handleOffer(h264, w, r) })
	mux.HandleFunc("/cursor", func(w http.ResponseWriter, r *http.Request) { handleCursor(h264, w, r) })
	mux.HandleFunc("/debug/status", func(w http.ResponseWriter, r *http.Request) { handleDebugStatus(h264, w, r) })
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = io.WriteString(w, "ok\n")
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" && r.URL.Path != "/index.html" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, browserHTML)
	})
	log.Printf("WebRTC gateway listening on %s, Android H264=%s", listen, android)
	log.Fatal(http.ListenAndServe(listen, mux))
}

type debugStatus struct {
	Source struct {
		Address        string `json:"address"`
		Connected      bool   `json:"connected"`
		ConnectCount   uint64 `json:"connectCount"`
		Width          int    `json:"width"`
		Height         int    `json:"height"`
		Frames         uint64 `json:"frames"`
		KeyFrames      uint64 `json:"keyFrames"`
		ConfigFrames   uint64 `json:"configFrames"`
		Bytes          uint64 `json:"bytes"`
		LastFrameSize  int    `json:"lastFrameSize"`
		LastFrameAt    string `json:"lastFrameAt,omitempty"`
		ProfileLevelID string `json:"profileLevelId,omitempty"`
		LastError      string `json:"lastError,omitempty"`
		PLI            uint64 `json:"pli"`
		FramesSent     uint64 `json:"framesSent"`
		KeyFramesSent  uint64 `json:"keyFramesSent"`
		ConfigSent     uint64 `json:"configSent"`
		WaitedNoConfig uint64 `json:"waitedNoConfig"`
	} `json:"source"`
	WebRTC struct {
		Note string `json:"note"`
	} `json:"webrtc"`
}

func handleCursor(src *h264Source, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	src.mu.RLock()
	c := src.cursor
	src.mu.RUnlock()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(c)
}

type cursorState struct {
	Visible bool `json:"visible"`
	X       int  `json:"x"`
	Y       int  `json:"y"`
	Width   int  `json:"width"`
	Height  int  `json:"height"`
}

func handleDebugStatus(src *h264Source, w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	src.mu.RLock()
	st := debugStatus{}
	st.Source.Address = src.addr
	st.Source.Connected = src.connected
	st.Source.ConnectCount = src.connectCount
	st.Source.Width = src.header.Width
	st.Source.Height = src.header.Height
	st.Source.Frames = src.frames
	st.Source.KeyFrames = src.keyFrames
	st.Source.ConfigFrames = src.configFrames
	st.Source.Bytes = src.bytes
	st.Source.LastFrameSize = src.lastFrameSize
	if !src.lastFrameAt.IsZero() {
		st.Source.LastFrameAt = src.lastFrameAt.Format(time.RFC3339Nano)
	}
	st.Source.ProfileLevelID = src.profileLevelID
	st.Source.LastError = src.lastError
	st.Source.PLI = src.pliCount
	st.Source.FramesSent = src.framesSent
	st.Source.KeyFramesSent = src.keyFramesSent
	st.Source.ConfigSent = src.configSent
	st.Source.WaitedNoConfig = src.waitedNoConfig
	src.mu.RUnlock()
	st.WebRTC.Note = "peer count is not persisted; inspect gateway log for peer state"
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(st)
}

func handleOffer(src *h264Source, w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	log.Printf("HTTP /offer from %s", r.RemoteAddr)
	offerSDP, err := io.ReadAll(io.LimitReader(r.Body, 256*1024))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	// Wait briefly for SPS/PPS. Without codec configuration the browser may
	// successfully establish WebRTC but cannot initialize its H264 decoder.
	profile := src.waitProfile(3 * time.Second)
	if profile == "" {
		profile = defaultH264ProfileLevelID
		log.Printf("[H264-SDP] WARNING: no SPS received before offer; using fallback profile-level-id=%s", profile)
	} else {
		log.Printf("[H264-SDP] source SPS profile-level-id=%s", profile)
	}

	fmtp := "level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=" + profile
	m := &webrtc.MediaEngine{}
	if err = m.RegisterCodec(webrtc.RTPCodecParameters{
		RTPCodecCapability: webrtc.RTPCodecCapability{
			MimeType:    webrtc.MimeTypeH264,
			ClockRate:   90000,
			SDPFmtpLine: fmtp,
			RTCPFeedback: []webrtc.RTCPFeedback{
				{Type: "nack"}, {Type: "nack", Parameter: "pli"}, {Type: "ccm", Parameter: "fir"},
			},
		}, PayloadType: 102,
	}, webrtc.RTPCodecTypeVideo); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	api := webrtc.NewAPI(webrtc.WithMediaEngine(m))
	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	track, err := webrtc.NewTrackLocalStaticSample(
		webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264, ClockRate: 90000, SDPFmtpLine: fmtp},
		"video", "virtualdisplay")
	if err != nil {
		http.Error(w, err.Error(), 500)
		_ = pc.Close()
		return
	}
	if _, err = pc.AddTrack(track); err != nil {
		http.Error(w, err.Error(), 500)
		_ = pc.Close()
		return
	}

	// Drain and inspect RTCP. A PLI/FIR means the browser needs a fresh keyframe.
	if sender := pc.GetSenders(); len(sender) > 0 {
		go func() {
			buf := make([]byte, 4096)
			for {
				n, _, e := sender[0].Read(buf)
				if e != nil {
					return
				}
				if n <= 0 {
					continue
				}
				pkts, e := rtcp.Unmarshal(buf[:n])
				if e != nil {
					log.Printf("[H264-RTCP] unmarshal failed: %v", e)
					continue
				}
				for _, pkt := range pkts {
					switch pkt.(type) {
					case *rtcp.PictureLossIndication, *rtcp.FullIntraRequest:
						src.mu.Lock()
						src.pliCount++
						src.lastPLIAt = time.Now()
						src.keyRequest++
						src.mu.Unlock()
						log.Printf("[H264-RTCP] keyframe requested by browser (%T)", pkt)
					case *rtcp.TransportLayerNack:
						// No source-side packet loss exists because VDH1 is TCP.
					}
				}
			}
		}()
	}

	pc.OnConnectionStateChange(func(s webrtc.PeerConnectionState) {
		log.Printf("peer=%s", s.String())
		if s == webrtc.PeerConnectionStateFailed || s == webrtc.PeerConnectionStateClosed {
			_ = pc.Close()
		}
	})

	if err = pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: string(offerSDP)}); err != nil {
		http.Error(w, err.Error(), 400)
		_ = pc.Close()
		return
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		http.Error(w, err.Error(), 500)
		_ = pc.Close()
		return
	}
	gatherComplete := webrtc.GatheringCompletePromise(pc)
	if err = pc.SetLocalDescription(answer); err != nil {
		http.Error(w, err.Error(), 500)
		_ = pc.Close()
		return
	}
	<-gatherComplete
	local := pc.LocalDescription()
	if local == nil {
		http.Error(w, "missing local description", 500)
		_ = pc.Close()
		return
	}

	log.Printf("WebRTC answer ready: peer=%s profile-level-id=%s", r.RemoteAddr, profile)
	go src.pipeTo(track, pc)
	w.Header().Set("Content-Type", "application/sdp")
	_, _ = io.WriteString(w, local.SDP)
}

func newH264Source(addr, controlAddr string) *h264Source {
	return &h264Source{addr: addr, controlAddr: controlAddr, changed: make(chan struct{})}
}

func (s *h264Source) pollCursor() {
	client := &http.Client{Timeout: 500 * time.Millisecond}
	url := "http://" + s.controlAddr + "/api/webrtc/cursor"
	ticker := time.NewTicker(33 * time.Millisecond)
	defer ticker.Stop()
	for range ticker.C {
		resp, err := client.Get(url)
		if err != nil {
			continue
		}
		var c cursorState
		err = json.NewDecoder(resp.Body).Decode(&c)
		resp.Body.Close()
		if err != nil {
			continue
		}
		s.mu.Lock()
		s.cursor = c
		s.mu.Unlock()
	}
}

func (s *h264Source) run() {
	for {
		if err := s.readOnce(); err != nil {
			log.Printf("H264 source: %v", err)
			time.Sleep(time.Second)
		}
	}
}

func (s *h264Source) readOnce() error {
	log.Printf("H264 source connecting: %s", s.addr)
	c, err := net.DialTimeout("tcp", s.addr, 3*time.Second)
	if err != nil {
		s.mu.Lock()
		s.connected = false
		s.lastError = err.Error()
		s.mu.Unlock()
		return err
	}
	s.mu.Lock()
	s.connected = true
	s.connectCount++
	s.lastError = ""
	s.mu.Unlock()
	log.Printf("H264 source connected: %s", s.addr)
	defer func() {
		c.Close()
		s.mu.Lock()
		s.connected = false
		s.mu.Unlock()
		log.Printf("H264 source disconnected: %s", s.addr)
	}()
	br := bufio.NewReaderSize(c, 256*1024)
	magicBuf := make([]byte, 4)
	if _, err = io.ReadFull(br, magicBuf); err != nil {
		return err
	}
	if string(magicBuf) != magic {
		return fmt.Errorf("bad magic %q", magicBuf)
	}
	var values [4]int32
	if err = binary.Read(br, binary.BigEndian, &values); err != nil {
		return err
	}
	s.mu.Lock()
	s.header.Width = int(values[2])
	s.header.Height = int(values[3])
	s.mu.Unlock()
	log.Printf("VDH1 header received: version=%d displayId=%d width=%d height=%d", values[0], values[1], values[2], values[3])

	for {
		typ, err := br.ReadByte()
		if err != nil {
			return err
		}
		switch typ {
		case typeFormat:
			var wh [8]byte
			if _, err = io.ReadFull(br, wh[:]); err != nil {
				return err
			}
			s.mu.Lock()
			s.header.Width = int(binary.BigEndian.Uint32(wh[:4]))
			s.header.Height = int(binary.BigEndian.Uint32(wh[4:]))
			s.mu.Unlock()
			log.Printf("H264 format: %dx%d", s.header.Width, s.header.Height)
		case typeFrame:
			var hdr [16]byte
			if _, err = io.ReadFull(br, hdr[:]); err != nil {
				return err
			}
			pts := int64(binary.BigEndian.Uint64(hdr[:8]))
			flags := int(binary.BigEndian.Uint32(hdr[8:12]))
			size := int(binary.BigEndian.Uint32(hdr[12:16]))
			if size <= 0 || size > 32*1024*1024 {
				return fmt.Errorf("invalid frame size %d", size)
			}
			data := make([]byte, size)
			if _, err = io.ReadFull(br, data); err != nil {
				return err
			}

			normalized, sps, pps, ok := normalizeH264(data)
			if !ok {
				return fmt.Errorf("unsupported H264 payload format, size=%d first=%x", size, firstBytes(data, 16))
			}

			// Some encoders do not set the key-frame flag consistently. Detect IDR
			// from the actual NAL units so a newly connected browser can start from
			// a decodable access unit.
			if hasNALType(normalized, 5) {
				flags |= flagKeyFrame
			}

			if flags&flagConfig != 0 || len(sps) > 0 || len(pps) > 0 {
				s.mu.Lock()
				if len(sps) > 0 {
					s.sps = append([]byte(nil), sps...)
					if p := profileLevelID(sps); p != "" {
						s.profileLevelID = p
					}
				}
				if len(pps) > 0 {
					s.pps = append([]byte(nil), pps...)
				}
				if len(s.sps) > 0 && len(s.pps) > 0 {
					s.config = annexBJoin(s.sps, s.pps)
				}
				cfgSize := len(s.config)
				profile := s.profileLevelID
				if cfgSize > 0 {
					s.configFrames++
				}
				s.mu.Unlock()
				if cfgSize > 0 {
					log.Printf("H264 config cached: size=%d profile-level-id=%s", cfgSize, profile)
				}
			}

			s.mu.Lock()
			s.seq++
			f := &h264Frame{seq: s.seq, ptsUs: pts, flags: flags, data: normalized}
			s.latest = f
			s.frames++
			s.bytes += uint64(len(normalized))
			s.lastFrameAt = time.Now()
			s.lastFrameSize = len(normalized)
			if flags&flagKeyFrame != 0 {
				s.latestKey = f
				s.keyFrames++
				log.Printf("H264 key frame: seq=%d pts=%d size=%d", f.seq, pts, len(normalized))
			}
			old := s.changed
			s.changed = make(chan struct{})
			close(old)
			s.mu.Unlock()
		default:
			return fmt.Errorf("unknown packet type %d", typ)
		}
	}
}

func (s *h264Source) waitProfile(timeout time.Duration) string {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		s.mu.RLock()
		p := s.profileLevelID
		ch := s.changed
		s.mu.RUnlock()
		if p != "" {
			return p
		}
		select {
		case <-ch:
		case <-time.After(100 * time.Millisecond):
		}
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.profileLevelID
}

func (s *h264Source) pipeTo(track *webrtc.TrackLocalStaticSample, pc *webrtc.PeerConnection) {
	var lastSeq uint64
	var lastPtsUs int64
	var ptsBaseUs int64 = -1
	var seenKeyRequest uint64
	loggedFirst := false

	for {
		s.mu.RLock()
		ch := s.changed
		f := s.latest
		key := s.latestKey
		cfg := append([]byte(nil), s.config...)
		currentKeyRequest := s.keyRequest
		s.mu.RUnlock()

		forceKey := currentKeyRequest != seenKeyRequest
		if forceKey {
			seenKeyRequest = currentKeyRequest
		}

		if f == nil || (lastSeq == 0 && key == nil) {
			select {
			case <-ch:
			case <-time.After(100 * time.Millisecond):
			}
			if pc.ConnectionState() == webrtc.PeerConnectionStateClosed ||
				pc.ConnectionState() == webrtc.PeerConnectionStateFailed {
				return
			}
			continue
		}

		if lastSeq == 0 {
			// Never start a peer on a P-frame. Decoder configuration and IDR
			// are both mandatory for the first sample.
			if key == nil || len(cfg) == 0 {
				s.mu.Lock()
				s.waitedNoConfig++
				s.mu.Unlock()
				select {
				case <-ch:
				case <-time.After(100 * time.Millisecond):
				}
				continue
			}
			f = key
		} else if forceKey && key != nil {
			f = key
		} else if f.seq == lastSeq {
			select {
			case <-ch:
			case <-time.After(100 * time.Millisecond):
			}
			continue
		}

		data := f.data
		isKey := f.flags&flagKeyFrame != 0

		// Every keyframe delivered to a peer is made self-contained:
		// SPS + PPS + IDR. This also recovers after a decoder reset or PLI.
		if isKey && len(cfg) > 0 && !containsSPSPPS(data) {
			merged := make([]byte, 0, len(cfg)+len(data))
			merged = append(merged, cfg...)
			merged = append(merged, data...)
			data = merged
		}

		if isKey && !containsSPSPPS(data) {
			s.mu.Lock()
			s.waitedNoConfig++
			s.mu.Unlock()
			select {
			case <-ch:
			case <-time.After(100 * time.Millisecond):
			}
			continue
		}

		if ptsBaseUs < 0 {
			ptsBaseUs = f.ptsUs
		}
		relativePtsUs := f.ptsUs - ptsBaseUs
		if relativePtsUs < 0 {
			relativePtsUs = 0
		}

		dur := time.Second / 30
		if lastSeq != 0 && relativePtsUs > lastPtsUs {
			d := time.Duration(relativePtsUs-lastPtsUs) * time.Microsecond
			if d >= time.Millisecond && d <= 500*time.Millisecond {
				dur = d
			}
		}
		lastPtsUs = relativePtsUs

		if err := track.WriteSample(media.Sample{Data: data, Duration: dur}); err != nil {
			log.Printf("WebRTC WriteSample failed: %v", err)
			_ = pc.Close()
			return
		}

		s.mu.Lock()
		s.framesSent++
		if isKey {
			s.keyFramesSent++
			s.configSent++
		}
		s.mu.Unlock()

		lastSeq = f.seq
		if !loggedFirst {
			s.mu.RLock()
			profile := s.profileLevelID
			s.mu.RUnlock()
			log.Printf(
				"WebRTC first H264 sample: seq=%d pts=%d key=%t size=%d duration=%s selfContained=%t profile-level-id=%s",
				f.seq, f.ptsUs, isKey, len(data), dur, containsSPSPPS(data), profile,
			)
			loggedFirst = true
		}

		if pc.ConnectionState() == webrtc.PeerConnectionStateClosed ||
			pc.ConnectionState() == webrtc.PeerConnectionStateFailed {
			return
		}
	}
}

func normalizeH264(b []byte) ([]byte, []byte, []byte, bool) {
	if len(b) == 0 {
		return nil, nil, nil, false
	}
	if hasAnnexBStartCode(b) {
		nals := splitAnnexB(b)
		var sps, pps []byte
		for _, n := range nals {
			if len(n) == 0 {
				continue
			}
			switch n[0] & 0x1f {
			case 7:
				sps = append([]byte(nil), n...)
			case 8:
				pps = append([]byte(nil), n...)
			}
		}
		return append([]byte(nil), b...), sps, pps, true
	}
	// AVCDecoderConfigurationRecord (ISO/IEC 14496-15), commonly used as MediaCodec CSD.
	if len(b) >= 7 && b[0] == 1 {
		if sps, pps, ok := parseAVCDecoderConfigurationRecord(b); ok {
			return annexBJoin(sps, pps), sps, pps, true
		}
	}
	// Length-prefixed AVCC access unit. Try 4-byte then 2-byte lengths.
	for _, n := range []int{4, 2} {
		if out, sps, pps, ok := parseLengthPrefixedNALs(b, n); ok {
			return out, sps, pps, true
		}
	}
	// Single raw NAL unit.
	if b[0]&0x1f >= 1 && b[0]&0x1f <= 23 {
		n := append([]byte(nil), b...)
		var sps, pps []byte
		if n[0]&0x1f == 7 {
			sps = n
		}
		if n[0]&0x1f == 8 {
			pps = n
		}
		return annexB(n), sps, pps, true
	}
	return nil, nil, nil, false
}

func parseAVCDecoderConfigurationRecord(b []byte) ([]byte, []byte, bool) {
	if len(b) < 7 || b[0] != 1 {
		return nil, nil, false
	}
	pos := 5
	numSPS := int(b[pos] & 0x1f)
	pos++
	var sps, pps []byte
	for i := 0; i < numSPS; i++ {
		if pos+2 > len(b) {
			return nil, nil, false
		}
		n := int(binary.BigEndian.Uint16(b[pos : pos+2]))
		pos += 2
		if n <= 0 || pos+n > len(b) {
			return nil, nil, false
		}
		if i == 0 {
			sps = append([]byte(nil), b[pos:pos+n]...)
		}
		pos += n
	}
	if pos >= len(b) {
		return nil, nil, false
	}
	numPPS := int(b[pos])
	pos++
	for i := 0; i < numPPS; i++ {
		if pos+2 > len(b) {
			return nil, nil, false
		}
		n := int(binary.BigEndian.Uint16(b[pos : pos+2]))
		pos += 2
		if n <= 0 || pos+n > len(b) {
			return nil, nil, false
		}
		if i == 0 {
			pps = append([]byte(nil), b[pos:pos+n]...)
		}
		pos += n
	}
	return sps, pps, len(sps) > 0 && len(pps) > 0
}

func parseLengthPrefixedNALs(b []byte, lengthBytes int) ([]byte, []byte, []byte, bool) {
	var out []byte
	var sps, pps []byte
	pos := 0
	count := 0
	for pos < len(b) {
		if pos+lengthBytes > len(b) {
			return nil, nil, nil, false
		}
		var n int
		if lengthBytes == 4 {
			n = int(binary.BigEndian.Uint32(b[pos : pos+4]))
		} else {
			n = int(binary.BigEndian.Uint16(b[pos : pos+2]))
		}
		pos += lengthBytes
		if n <= 0 || n > len(b)-pos {
			return nil, nil, nil, false
		}
		nal := b[pos : pos+n]
		pos += n
		if nal[0]&0x1f == 0 {
			return nil, nil, nil, false
		}
		out = append(out, annexB(nal)...)
		count++
		switch nal[0] & 0x1f {
		case 7:
			sps = append([]byte(nil), nal...)
		case 8:
			pps = append([]byte(nil), nal...)
		}
	}
	return out, sps, pps, count > 0
}

func splitAnnexB(b []byte) [][]byte {
	var out [][]byte
	i := 0
	for {
		start, sc := findStartCode(b, i)
		if start < 0 {
			break
		}
		nstart := start + sc
		end, _ := findStartCode(b, nstart)
		if end < 0 {
			end = len(b)
		}
		if end > nstart {
			out = append(out, b[nstart:end])
		}
		i = end
	}
	return out
}
func findStartCode(b []byte, from int) (int, int) {
	for i := from; i+3 <= len(b); i++ {
		if i+3 <= len(b) && bytes.Equal(b[i:i+3], []byte{0, 0, 1}) {
			return i, 3
		}
		if i+4 <= len(b) && bytes.Equal(b[i:i+4], []byte{0, 0, 0, 1}) {
			return i, 4
		}
	}
	return -1, 0
}
func hasAnnexBStartCode(b []byte) bool { _, n := findStartCode(b, 0); return n > 0 }
func annexB(n []byte) []byte {
	o := make([]byte, 4+len(n))
	copy(o, []byte{0, 0, 0, 1})
	copy(o[4:], n)
	return o
}
func annexBJoin(a, b []byte) []byte {
	var o []byte
	if len(a) > 0 {
		o = append(o, annexB(a)...)
	}
	if len(b) > 0 {
		o = append(o, annexB(b)...)
	}
	return o
}
func hasNALType(b []byte, want byte) bool {
	for _, n := range splitAnnexB(b) {
		if len(n) > 0 && (n[0]&0x1f) == want {
			return true
		}
	}
	return false
}

func containsSPSPPS(b []byte) bool {
	var s, p bool
	for _, n := range splitAnnexB(b) {
		if len(n) == 0 {
			continue
		}
		if n[0]&0x1f == 7 {
			s = true
		}
		if n[0]&0x1f == 8 {
			p = true
		}
	}
	return s && p
}
func profileLevelID(sps []byte) string {
	if len(sps) < 4 {
		return ""
	}
	return fmt.Sprintf("%02x%02x%02x", sps[1], sps[2], sps[3])
}
func firstBytes(b []byte, n int) string {
	if len(b) < n {
		n = len(b)
	}
	return fmt.Sprintf("% x", b[:n])
}

func getenv(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}
