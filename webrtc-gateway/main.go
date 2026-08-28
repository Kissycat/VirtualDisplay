package main

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/pion/interceptor"
	"github.com/pion/rtcp"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
)

const (
	// Built-in scrcpy daemon socket roles.
	roleVideo       = 0
	roleAudio       = 1
	roleControl     = 2
	roleNegotiation = 3

	// Current daemon protocol message.
	typeConfigureSession = 216
	typeGenericResponse  = 100

	// Codec identifiers used by the built-in scrcpy daemon.
	// The reference scrcpy Java project normally uses the ASCII token H264,
	// while this VirtualDisplay build exposes its internal enum/tag as 0x20000000.
	codecH264ID       = 0x68323634
	codecH264LegacyID = 0x20000000

	// Streamer packet flags.
	packetFlagSession  uint32 = 1 << 31
	packetFlagConfig   uint64 = 1 << 62
	packetFlagKeyFrame uint64 = 1 << 61

	flagConfig   = 1 << 0
	flagKeyFrame = 1 << 1

	defaultH264ProfileLevelID = "42e032"
	frameHistoryWindow        = 4 * time.Second
	frameHistoryMaxFrames     = 600
	maxFramePayload           = 32 * 1024 * 1024
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
#wrap{position:relative;flex:1;min-height:0;overflow:hidden;background:#000}
#video{position:absolute;left:50%;top:50%;display:block;background:#000;object-fit:fill;transform:translate(-50%,-50%);transform-origin:center center}
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
let expectedW=0, expectedH=0;
let sourceW=0, sourceH=0;
let rotated=false;

function setStatus(s){status.textContent=s;console.log('[WebRTC]',s);}

function fitRect(aspect,w,h){
  if(!aspect || w<=0 || h<=0) return {w:0,h:0};
  let tw=w, th=Math.round(w/aspect);
  if(th>h){th=h;tw=Math.round(h*aspect);}
  return {w:tw,h:th};
}

function applyVideoLayout(){
  const w=wrap.clientWidth, h=wrap.clientHeight;
  if(w<=0 || h<=0) return;

  const logicalW=expectedW||sourceW||video.videoWidth;
  const logicalH=expectedH||sourceH||video.videoHeight;
  if(logicalW<=0 || logicalH<=0) return;

  const srcW=sourceW||video.videoWidth;
  const srcH=sourceH||video.videoHeight;
  rotated = expectedW>0 && expectedH>0 && srcW>0 && srcH>0 &&
            ((expectedW>expectedH)!=(srcW>srcH));

  const r=fitRect(logicalW/logicalH,w,h);
  if(r.w<=0 || r.h<=0) return;

  if(rotated){
    // The decoder produces portrait-coded H264 while the selected virtual
    // display is landscape. Lay out the pre-rotation buffer with swapped
    // dimensions, then rotate it in the browser so the displayed rectangle
    // is still exactly the virtual display's logical aspect ratio.
    video.style.width=r.h+'px';
    video.style.height=r.w+'px';
    video.style.transform='translate(-50%,-50%) rotate(90deg)';
  }else{
    video.style.width=r.w+'px';
    video.style.height=r.h+'px';
    video.style.transform='translate(-50%,-50%)';
  }
}

function updateCursor(c){
  if(!c || !c.visible || !c.width || !c.height || !video.videoWidth || !video.videoHeight){
    cursor.style.display='none'; return;
  }
  const vr=video.getBoundingClientRect(), wr=wrap.getBoundingClientRect();
  if(vr.width<=0||vr.height<=0){cursor.style.display='none';return;}
  // Browser cursor is optional; local Android uses its own overlay. For the
  // Web page, map against the rotated visual rectangle rather than raw buffer.
  let nx=c.x/c.width, ny=c.y/c.height;
  if(rotated){
    const tx=nx, ty=ny;
    nx=1-ty; ny=tx;
  }
  cursor.style.left=(vr.left-wr.left+nx*vr.width)+'px';
  cursor.style.top=(vr.top-wr.top+ny*vr.height)+'px';
  cursor.style.display='block';
}

async function refreshStatus(){
  try{
    const r=await fetch('/debug/status',{cache:'no-store'});
    if(!r.ok)return;
    const s=await r.json();
    expectedW=Number(s.source?.expectedWidth||0);
    expectedH=Number(s.source?.expectedHeight||0);
    sourceW=Number(s.source?.width||0);
    sourceH=Number(s.source?.height||0);
    applyVideoLayout();
  }catch(e){}
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
  pc.ontrack=e=>{
    video.srcObject=(e.streams&&e.streams[0])?e.streams[0]:new MediaStream([e.track]);
    video.play().catch(()=>{});
    applyVideoLayout();
  };
  video.onloadedmetadata=()=>{
    sourceW=video.videoWidth; sourceH=video.videoHeight;
    applyVideoLayout();
  };
  pc.oniceconnectionstatechange=()=>setStatus('ICE: '+pc.iceConnectionState);
  pc.onconnectionstatechange=()=>setStatus('PC: '+pc.connectionState);
  try{
    await refreshStatus();
    const offer=await pc.createOffer({offerToReceiveVideo:true});
    await pc.setLocalDescription(offer);
    await new Promise(resolve=>{
      if(pc.iceGatheringState==='complete')return resolve();
      const timer=setTimeout(resolve,5000);
      pc.onicegatheringstatechange=()=>{
        if(pc.iceGatheringState==='complete'){clearTimeout(timer);resolve();}
      };
    });
    const res=await fetch('/offer',{method:'POST',headers:{'Content-Type':'application/sdp'},body:pc.localDescription.sdp});
    if(!res.ok)throw new Error('HTTP '+res.status+' '+await res.text());
    await pc.setRemoteDescription({type:'answer',sdp:await res.text()});
    await refreshStatus();
    setStatus('connected');
    setTimeout(applyVideoLayout,100);
  }catch(e){
    console.error('[WebRTC]',e);setStatus('error: '+e.message);
    if(pc){try{pc.close()}catch(_){}pc=null;}
  }
}
window.addEventListener('resize',applyVideoLayout);
document.getElementById('reconnect').onclick=start;
start(); pollCursor(); setInterval(refreshStatus,1000);
</script>
</body>
</html>`

type streamHeader struct {
	Width  int
	Height int
}

type h264Frame struct {
	seq        uint64
	ptsUs      int64
	flags      int
	data       []byte // normalized Annex-B access unit
	receivedAt time.Time
}

type h264Source struct {
	addr           string
	token          string
	displayID      int
	expectedWidth  int
	expectedHeight int

	mu             sync.RWMutex
	header         streamHeader
	config         []byte
	sps            []byte
	pps            []byte
	profileLevelID string
	latest         *h264Frame
	latestKey      *h264Frame
	history        []*h264Frame
	seq            uint64
	changed        chan struct{}
	keyRequest     uint64

	// Persistent daemon negotiation socket keeps the server-side session alive.
	sessionMu sync.Mutex
	session   net.Conn
	sessionID int32

	// Current ROLE_VIDEO socket. Protected so diagnostics/recovery can close it safely.
	videoMu   sync.Mutex
	videoConn net.Conn

	// Diagnostics / stream state.
	connected        bool
	connectCount     uint64
	frames           uint64
	keyFrames        uint64
	configFrames     uint64
	bytes            uint64
	lastFrameAt      time.Time
	lastFrameSize    int
	lastError        string
	pliCount         uint64
	lastPLIAt        time.Time
	lastKeyRequestAt time.Time
	framesSent       uint64
	keyFramesSent    uint64
	configSent       uint64
	waitedNoConfig   uint64
	frameOverruns    uint64
	recoveryCount    uint64
	cursor           cursorState
}

func main() {
	testMode := flag.Bool("t", false, "test mode: allow direct startup without DISPLAY_ID; defaults to display 0")
	flagDisplayID := flag.Int("display-id", -1, "scrcpy display id; overrides DISPLAY_ID")
	flagDaemon := flag.String("daemon", "", "scrcpy daemon address; overrides DAEMON_ADDR")
	flagListen := flag.String("listen", "", "HTTP listen address; overrides LISTEN")
	flagToken := flag.String("token", "", "scrcpy daemon token; overrides DAEMON_TOKEN")
	flagExpectedWidth := flag.Int("expected-width", -1, "expected virtual display width; overrides EXPECTED_WIDTH")
	flagExpectedHeight := flag.Int("expected-height", -1, "expected virtual display height; overrides EXPECTED_HEIGHT")
	flagCursorURL := flag.String("cursor-url", "", "cursor JSON endpoint; overrides CURSOR_URL")
	flag.Parse()

	daemonAddr := getenv("DAEMON_ADDR", "127.0.0.1:27183")
	if *flagDaemon != "" {
		daemonAddr = *flagDaemon
	}
	token := os.Getenv("DAEMON_TOKEN")
	if *flagToken != "" {
		token = *flagToken
	}
	displayID := -1
	if *flagDisplayID >= 0 {
		displayID = *flagDisplayID
	} else if displayEnv := strings.TrimSpace(os.Getenv("DISPLAY_ID")); displayEnv != "" {
		var err error
		displayID, err = strconv.Atoi(displayEnv)
		if err != nil || displayID < 0 {
			log.Fatalf("invalid DISPLAY_ID=%q", displayEnv)
		}
	} else if *testMode {
		// Explicit test mode may intentionally bind the daemon's default display.
		displayID = 0
	} else {
		log.Fatalf("DISPLAY_ID is required; use -t only for direct test startup or set DISPLAY_ID explicitly")
	}
	expectedWidth, _ := strconv.Atoi(getenv("EXPECTED_WIDTH", "0"))
	expectedHeight, _ := strconv.Atoi(getenv("EXPECTED_HEIGHT", "0"))
	if *flagExpectedWidth >= 0 {
		expectedWidth = *flagExpectedWidth
	}
	if *flagExpectedHeight >= 0 {
		expectedHeight = *flagExpectedHeight
	}
	listen := getenv("LISTEN", ":19000")
	if *flagListen != "" {
		listen = *flagListen
	}
	cursorURL := os.Getenv("CURSOR_URL")
	if *flagCursorURL != "" {
		cursorURL = *flagCursorURL
	}

	source := newH264Source(daemonAddr, token, displayID, expectedWidth, expectedHeight)
	go source.run()
	if cursorURL != "" {
		go source.pollCursorURL(cursorURL)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/offer", func(w http.ResponseWriter, r *http.Request) { handleOffer(source, w, r) })
	mux.HandleFunc("/cursor", func(w http.ResponseWriter, r *http.Request) { handleCursor(source, w, r) })
	mux.HandleFunc("/debug/status", func(w http.ResponseWriter, r *http.Request) { handleDebugStatus(source, w, r) })
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

	log.Printf("WebRTC gateway listening on %s; scrcpy daemon=%s displayId=%d", listen, daemonAddr, displayID)
	log.Fatal(http.ListenAndServe(listen, mux))
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

type debugStatus struct {
	Source struct {
		Address        string `json:"address"`
		Connected      bool   `json:"connected"`
		ConnectCount   uint64 `json:"connectCount"`
		Width          int    `json:"width"`
		Height         int    `json:"height"`
		ExpectedWidth  int    `json:"expectedWidth"`
		ExpectedHeight int    `json:"expectedHeight"`
		DisplayID      int    `json:"displayId"`
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
		Note string `json:"note,omitempty"`
	} `json:"webrtc"`
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
	st.Source.ExpectedWidth = src.expectedWidth
	st.Source.ExpectedHeight = src.expectedHeight
	st.Source.DisplayID = src.displayID
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

	// 创建拦截器注册表
	i := &interceptor.Registry{}

	// 注册默认拦截器 (提供 NACK 丢包重传、RTCP 报告等基础能力)
	if err = webrtc.RegisterDefaultInterceptors(m, i); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// 将拦截器注入到 API 中
	api := webrtc.NewAPI(webrtc.WithMediaEngine(m), webrtc.WithInterceptorRegistry(i))
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
			for {
				pkts, _, e := sender[0].ReadRTCP()
				if e != nil {
					return
				}
				for _, pkt := range pkts {
					switch pkt.(type) {
					case *rtcp.PictureLossIndication, *rtcp.FullIntraRequest:
						now := time.Now()
						src.mu.Lock()
						src.pliCount++
						src.lastPLIAt = now
						// Repeated PLI/FIR packets can arrive every few milliseconds while the
						// decoder is waiting. Do not turn that into an IDR storm; one recovery
						// request per 500 ms is sufficient because the latest keyframe is cached.
						request := src.lastKeyRequestAt.IsZero() || now.Sub(src.lastKeyRequestAt) >= 500*time.Millisecond
						if request {
							src.keyRequest++
							src.lastKeyRequestAt = now
						}
						src.mu.Unlock()
						if request {
							log.Printf("[H264-RTCP] keyframe requested by browser (%T)", pkt)
						}
					case *rtcp.TransportLayerNack:
						// The source is TCP, so packet loss is recovered by the H264 keyframe path rather than RTP retransmission.
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

func (s *h264Source) waitProfile(timeout time.Duration) string {
	deadline := time.Now().Add(timeout)
	for {
		s.mu.RLock()
		profile := s.profileLevelID
		haveConfig := len(s.sps) > 0 && len(s.pps) > 0
		ch := s.changed
		s.mu.RUnlock()
		if profile != "" && haveConfig {
			return profile
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return profile
		}
		t := time.NewTimer(remaining)
		select {
		case <-ch:
			if !t.Stop() {
				select {
				case <-t.C:
				default:
				}
			}
		case <-t.C:
			return profile
		}
	}
}

func newH264Source(addr, token string, displayID, expectedWidth, expectedHeight int) *h264Source {
	h := streamHeader{Width: expectedWidth, Height: expectedHeight}
	return &h264Source{
		addr:           addr,
		token:          token,
		displayID:      displayID,
		expectedWidth:  expectedWidth,
		expectedHeight: expectedHeight,
		header:         h,
		changed:        make(chan struct{}),
	}
}

func (s *h264Source) run() {
	for {
		if err := s.readOnce(); err != nil {
			s.mu.Lock()
			s.lastError = err.Error()
			s.connected = false
			s.mu.Unlock()
			log.Printf("scrcpy daemon source: %v", err)
			time.Sleep(500 * time.Millisecond)
		}
	}
}

func (s *h264Source) readOnce() error {
	if err := s.ensureSession(); err != nil {
		return err
	}

	conn, err := s.openVideoSocket()
	if err != nil {
		// If the configured session vanished, force a fresh negotiation on next attempt.
		s.dropSession()
		return err
	}

	s.videoMu.Lock()
	s.videoConn = conn
	s.videoMu.Unlock()

	s.mu.Lock()
	s.connected = true
	s.connectCount++
	s.lastError = ""
	s.mu.Unlock()

	defer func() {
		_ = conn.Close()
		s.videoMu.Lock()
		if s.videoConn == conn {
			s.videoConn = nil
		}
		s.videoMu.Unlock()
		s.mu.Lock()
		s.connected = false
		s.mu.Unlock()
	}()

	return s.readScrcpyVideoStream(conn)
}

func (s *h264Source) ensureSession() error {
	s.sessionMu.Lock()
	defer s.sessionMu.Unlock()
	if s.session != nil {
		return nil
	}

	log.Printf("scrcpy daemon negotiation connecting: %s", s.addr)
	c, err := net.DialTimeout("tcp", s.addr, 3*time.Second)
	if err != nil {
		return err
	}
	configureTCP(c)

	br := bufio.NewReaderSize(c, 64*1024)
	if err := writeU8(c, roleNegotiation); err != nil {
		_ = c.Close()
		return err
	}

	sid, err := readI32(br)
	if err != nil {
		_ = c.Close()
		return err
	}

	if s.token != "" {
		if err := writeString32(c, s.token); err != nil {
			_ = c.Close()
			return err
		}
	}

	// Device meta is emitted by ClientSession after authentication/session creation.
	meta := make([]byte, 64)
	if _, err := io.ReadFull(br, meta); err != nil {
		_ = c.Close()
		return err
	}

	options := strings.Join([]string{
		"video=true",
		"audio=false",
		"video_codec=h264",
		"send_stream_meta=false",
		"send_frame_meta=true",
	}, "\n") + "\n"

	const seq = int64(1)
	if err := writeConfigureSession(c, seq, options, []roleEntry{{role: roleVideo, displayID: int32(s.displayID)}}); err != nil {
		_ = c.Close()
		return err
	}

	c.SetReadDeadline(time.Now().Add(5 * time.Second))
	response, err := readGenericResponse(br)
	if err != nil {
		_ = c.Close()
		return fmt.Errorf("CONFIGURE_SESSION response: %w", err)
	}
	c.SetReadDeadline(time.Time{})

	if response.sequence != seq {
		_ = c.Close()
		return fmt.Errorf("CONFIGURE_SESSION sequence mismatch: got %d want %d", response.sequence, seq)
	}
	if response.statusCode != 0 {
		_ = c.Close()
		return fmt.Errorf("CONFIGURE_SESSION failed: status=%d message=%s", response.statusCode, response.message)
	}

	s.session = c
	s.sessionID = sid
	log.Printf("scrcpy daemon session established: sessionId=%d device=%q", sid, trimCString(meta))
	return nil
}

func (s *h264Source) openVideoSocket() (net.Conn, error) {
	s.sessionMu.Lock()
	sid := s.sessionID
	sessionAlive := s.session != nil
	s.sessionMu.Unlock()
	if !sessionAlive {
		return nil, fmt.Errorf("daemon session not established")
	}

	c, err := net.DialTimeout("tcp", s.addr, 3*time.Second)
	if err != nil {
		return nil, err
	}
	configureTCP(c)

	if err := writeU8(c, roleVideo); err != nil {
		_ = c.Close()
		return nil, err
	}
	if err := writeI32(c, sid); err != nil {
		_ = c.Close()
		return nil, err
	}
	if err := writeI32(c, int32(s.displayID)); err != nil {
		_ = c.Close()
		return nil, err
	}

	br := bufio.NewReaderSize(c, 256*1024)
	ack, err := readI32(br)
	if err != nil {
		_ = c.Close()
		return nil, err
	}
	if int(ack) != s.displayID {
		_ = c.Close()
		return nil, fmt.Errorf("ROLE_VIDEO displayId ack mismatch: got %d want %d", ack, s.displayID)
	}

	// From here the socket is exactly the daemon's scrcpy Streamer output:
	//   int32 codecId
	//   int32 sessionFlags + int32 width + int32 height
	//   repeated: uint64 ptsAndFlags + int32 payloadSize + payload
	// The buffered reader must remain associated with the connection for the whole stream.
	return &bufferedConn{Conn: c, r: br}, nil
}

type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.r.Read(p) }

func (s *h264Source) readScrcpyVideoStream(conn net.Conn) error {
	bc, ok := conn.(*bufferedConn)
	if !ok {
		return fmt.Errorf("internal video connection type error")
	}

	// The daemon's FrameBroadcaster is shared per display. If another client
	// (notably the Android app itself) created it first, its original
	// send_stream_meta=false setting is baked into the broadcaster. In that
	// case the ROLE_VIDEO socket starts directly with FrameMeta, without a
	// codec-id or SessionMeta. Our gateway therefore accepts BOTH forms:
	//
	//   A) codec header + optional SessionMeta + FrameMeta
	//   B) FrameMeta-only (the normal path for the app's existing subscriber)
	//
	// We prefer B for newly-created broadcasters by requesting
	// send_stream_meta=false in ensureSession().
	hasCodecHeader := false
	codec := uint32(codecH264ID)
	if peek, e := bc.r.Peek(12); e == nil {
		first := binary.BigEndian.Uint32(peek[0:4])
		second := binary.BigEndian.Uint32(peek[4:8])
		sz := int64(int32(binary.BigEndian.Uint32(peek[8:12])))
		looksLikeCodec := first == codecH264ID
		if first == codecH264LegacyID {
			// Legacy/internal H264 is also the high 32 bits of the KEY_FRAME
			// flag, so distinguish a real codec header from a frame-meta-only
			// stream by looking for a following meta/flag word or an implausibly
			// large PTS high-half.
			looksLikeCodec = (second&0xC0000000) != 0 || second > 0x00FFFFFF
		}
		if looksLikeCodec {
			_, _ = io.ReadFull(bc.r, peek[0:4])
			codecID, e := readI32From4(peek[0:4])
			if e != nil {
				return e
			}
			codec = uint32(codecID)
			if codec != codecH264ID && codec != codecH264LegacyID {
				return fmt.Errorf("unsupported scrcpy video codec id=0x%08x (want H264=0x%08x or legacy=0x%08x)", codec, codecH264ID, codecH264LegacyID)
			}
			hasCodecHeader = true
			if codec == codecH264LegacyID {
				log.Printf("scrcpy ROLE_VIDEO reports legacy/internal H264 codec id=0x%08x", codec)
			}
		} else if first == codecH264LegacyID && sz >= 1 && sz <= maxFramePayload {
			log.Printf("scrcpy ROLE_VIDEO detected frame-meta-only legacy-keyframe stream (displayId=%d)", s.displayID)
		}
	}

	log.Printf("scrcpy ROLE_VIDEO connected: displayId=%d codec=H264 framing=%s", s.displayID, func() string {
		if hasCodecHeader {
			return "codec-header"
		}
		return "frame-meta-only"
	}())

	for {
		// scrcpy Streamer multiplexes two different 12-byte records:
		//   1) SessionMeta: uint32(flags) | uint32(width) | uint32(height)
		//      with bit31 of flags set; NO payload follows.
		//   2) FrameMeta: uint64(ptsAndFlags) | uint32(payloadSize),
		//      followed by payload bytes.
		// Read the first 4 bytes first so SessionMeta can be recognized without
		// consuming the following frame header as a gigantic payload size.
		var first4 [4]byte
		if _, err := io.ReadFull(bc.r, first4[:]); err != nil {
			return err
		}

		first := binary.BigEndian.Uint32(first4[:])
		if hasCodecHeader && first&0x80000000 != 0 {
			var metaRest [8]byte
			if _, err := io.ReadFull(bc.r, metaRest[:]); err != nil {
				return err
			}
			width := binary.BigEndian.Uint32(metaRest[0:4])
			height := binary.BigEndian.Uint32(metaRest[4:8])
			s.mu.Lock()
			s.header.Width = int(width)
			s.header.Height = int(height)
			s.mu.Unlock()
			log.Printf("scrcpy ROLE_VIDEO session meta: displayId=%d %dx%d flags=0x%08x", s.displayID, width, height, first)
			continue
		}

		// FrameMeta is exactly 12 bytes total: uint64 ptsAndFlags + uint32 payloadSize.
		// We already consumed the first 4 bytes above, so consume only the remaining
		// 4 bytes of ptsAndFlags, then the 4-byte payload size. The previous version
		// consumed 8 bytes here and therefore swallowed the first 4 bytes of the H264
		// payload (for example 67 42 00 0a), producing the bogus size 0x6742000a.
		var ptsLow4 [4]byte
		if _, err := io.ReadFull(bc.r, ptsLow4[:]); err != nil {
			return err
		}
		var ptsBytes [8]byte
		copy(ptsBytes[0:4], first4[:])
		copy(ptsBytes[4:8], ptsLow4[:])
		rawFlags := binary.BigEndian.Uint64(ptsBytes[:])
		var size4 [4]byte
		if _, err := io.ReadFull(bc.r, size4[:]); err != nil {
			return err
		}
		size := int32(binary.BigEndian.Uint32(size4[:]))
		if size <= 0 || size > maxFramePayload {
			return fmt.Errorf("invalid scrcpy frame size=%d", size)
		}

		data := make([]byte, int(size))
		if _, err := io.ReadFull(bc.r, data); err != nil {
			return err
		}

		config := (rawFlags & packetFlagConfig) != 0
		key := (rawFlags & packetFlagKeyFrame) != 0
		pts := int64(rawFlags &^ (packetFlagConfig | packetFlagKeyFrame))

		normalized, sps, pps, ok := normalizeH264(data)
		if !ok {
			return fmt.Errorf("unsupported H264 payload size=%d first=%s", len(data), firstBytes(data, 16))
		}

		if hasNALType(normalized, 5) {
			key = true
		}
		flags := 0
		if config {
			flags |= flagConfig
		}
		if key {
			flags |= flagKeyFrame
		}

		now := time.Now()

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
		if config && len(s.config) > 0 {
			s.configFrames++
		}

		s.seq++
		f := &h264Frame{
			seq:        s.seq,
			ptsUs:      pts,
			flags:      flags,
			data:       normalized,
			receivedAt: now,
		}
		s.latest = f
		s.history = append(s.history, f)

		cutoff := now.Add(-frameHistoryWindow)
		firstKeep := 0
		for firstKeep < len(s.history) &&
			(len(s.history)-firstKeep > frameHistoryMaxFrames || s.history[firstKeep].receivedAt.Before(cutoff)) {
			firstKeep++
		}
		if firstKeep > 0 {
			s.history = append([]*h264Frame(nil), s.history[firstKeep:]...)
		}

		s.frames++
		s.bytes += uint64(len(normalized))
		s.lastFrameAt = now
		s.lastFrameSize = len(normalized)
		if key {
			s.latestKey = f
			s.keyFrames++
		}

		old := s.changed
		s.changed = make(chan struct{})
		close(old)
		s.mu.Unlock()
	}
}

func (s *h264Source) dropSession() {
	s.sessionMu.Lock()
	if s.session != nil {
		_ = s.session.Close()
	}
	s.session = nil
	s.sessionID = 0
	s.sessionMu.Unlock()
}

func (s *h264Source) pollCursorURL(url string) {
	client := &http.Client{Timeout: 500 * time.Millisecond}
	ticker := time.NewTicker(33 * time.Millisecond)
	defer ticker.Stop()
	for range ticker.C {
		resp, err := client.Get(url)
		if err != nil {
			continue
		}
		var c cursorState
		err = json.NewDecoder(resp.Body).Decode(&c)
		_ = resp.Body.Close()
		if err != nil {
			continue
		}
		s.mu.Lock()
		s.cursor = c
		s.mu.Unlock()
	}
}

// ---- daemon protocol helpers ----

type roleEntry struct {
	role      int
	displayID int32
}

func configureTCP(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
		_ = tc.SetReadBuffer(1024 * 1024)
		_ = tc.SetWriteBuffer(1024 * 1024)
		_ = tc.SetKeepAlive(true)
	}
}

func writeU8(c net.Conn, v int) error {
	_, err := c.Write([]byte{byte(v)})
	return err
}

func writeI32(c net.Conn, v int32) error {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], uint32(v))
	_, err := c.Write(b[:])
	return err
}

func writeU32(c net.Conn, v uint32) error {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], v)
	_, err := c.Write(b[:])
	return err
}

func writeU64(c net.Conn, v uint64) error {
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], v)
	_, err := c.Write(b[:])
	return err
}

func writeString32(c net.Conn, v string) error {
	b := []byte(v)
	if err := writeI32(c, int32(len(b))); err != nil {
		return err
	}
	_, err := c.Write(b)
	return err
}

func writeConfigureSession(c net.Conn, seq int64, options string, entries []roleEntry) error {
	if err := writeU8(c, typeConfigureSession); err != nil {
		return err
	}
	if err := writeU64(c, uint64(seq)); err != nil {
		return err
	}
	if err := writeString32(c, options); err != nil {
		return err
	}
	if err := writeI32(c, 0); err != nil { // legacy rolesMask: unused when entries are present
		return err
	}
	if err := writeI32(c, int32(len(entries))); err != nil {
		return err
	}
	for _, e := range entries {
		if err := writeU8(c, e.role); err != nil {
			return err
		}
		if err := writeI32(c, e.displayID); err != nil {
			return err
		}
	}
	return nil
}

type genericResponse struct {
	sequence   int64
	statusCode int32
	displayID  int32
	message    string
}

func readGenericResponse(r io.Reader) (genericResponse, error) {
	typ, err := readU8(r)
	if err != nil {
		return genericResponse{}, err
	}
	if typ != typeGenericResponse {
		return genericResponse{}, fmt.Errorf("unexpected daemon response type=%d", typ)
	}
	seq, err := readI64(r)
	if err != nil {
		return genericResponse{}, err
	}
	status, err := readI32(r)
	if err != nil {
		return genericResponse{}, err
	}
	display, err := readI32(r)
	if err != nil {
		return genericResponse{}, err
	}
	n, err := readI32(r)
	if err != nil {
		return genericResponse{}, err
	}
	if n < 0 || n > 1<<20 {
		return genericResponse{}, fmt.Errorf("invalid daemon response message length=%d", n)
	}
	b := make([]byte, int(n))
	if _, err := io.ReadFull(r, b); err != nil {
		return genericResponse{}, err
	}
	return genericResponse{
		sequence:   seq,
		statusCode: status,
		displayID:  display,
		message:    string(b),
	}, nil
}

func readU8(r io.Reader) (byte, error) {
	var b [1]byte
	_, err := io.ReadFull(r, b[:])
	return b[0], err
}

func readI32From4(b []byte) (int32, error) {
	if len(b) != 4 {
		return 0, fmt.Errorf("expected exactly 4 bytes, got %d", len(b))
	}
	return int32(binary.BigEndian.Uint32(b)), nil
}

func readI32(r io.Reader) (int32, error) {
	var b [4]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return int32(binary.BigEndian.Uint32(b[:])), nil
}

func readU32(r io.Reader) (uint32, error) {
	var b [4]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint32(b[:]), nil
}

func readU64(r io.Reader) (uint64, error) {
	var b [8]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint64(b[:]), nil
}

func readI64(r io.Reader) (int64, error) {
	var b [8]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return int64(binary.BigEndian.Uint64(b[:])), nil
}

func trimCString(b []byte) string {
	if i := bytes.IndexByte(b, 0); i >= 0 {
		b = b[:i]
	}
	return string(b)
}
func (s *h264Source) pipeTo(track *webrtc.TrackLocalStaticSample, pc *webrtc.PeerConnection) {
	var lastSeq uint64
	var ptsBaseUs int64 = -1
	var lastPtsUs int64 = -1
	var forceKeySeen uint64
	var pending *h264Frame
	var pendingData []byte
	var pendingKey bool
	var pendingRelPtsUs int64
	loggedFirst := false

	// Small one-frame look-ahead: with VFR input, the duration of the current
	// sample is the delta to the next sample, not the delta from the previous one.
	// This keeps RTP pacing faithful to the real source cadence.
	flushPending := func(nextPtsUs int64, hasNext bool) error {
		if pending == nil {
			return nil
		}
		dur := time.Second / 60
		if hasNext && nextPtsUs >= pendingRelPtsUs {
			d := time.Duration(nextPtsUs-pendingRelPtsUs) * time.Microsecond
			if d >= 1*time.Millisecond && d <= 2*time.Second {
				dur = d
			}
		}
		if err := track.WriteSample(media.Sample{Data: pendingData, PacketTimestamp: uint32((pendingRelPtsUs * 90) / 1000), Duration: dur}); err != nil {
			return err
		}
		s.mu.Lock()
		s.framesSent++
		if pendingKey {
			s.keyFramesSent++
			s.configSent++
		}
		s.mu.Unlock()
		if !loggedFirst {
			s.mu.RLock()
			profile := s.profileLevelID
			s.mu.RUnlock()
			log.Printf("WebRTC first H264 sample: seq=%d pts=%d key=%t size=%d duration=%s selfContained=%t profile-level-id=%s",
				pending.seq, pending.ptsUs, pendingKey, len(pendingData), dur, containsSPSPPS(pendingData), profile)
			loggedFirst = true
		}
		pending = nil
		pendingData = nil
		pendingKey = false
		lastPtsUs = pendingRelPtsUs
		return nil
	}

	for {
		s.mu.RLock()
		ch := s.changed
		latestSeq := s.seq
		key := s.latestKey
		cfg := append([]byte(nil), s.config...)
		currentKeyRequest := s.keyRequest
		var next *h264Frame
		var overrun bool

		if lastSeq == 0 {
			next = key
		} else {
			target := lastSeq + 1
			for _, candidate := range s.history {
				if candidate.seq == target {
					next = candidate
					break
				}
			}
			if next == nil && latestSeq >= target && len(s.history) > 0 && s.history[0].seq > target {
				overrun = true
			}
		}
		s.mu.RUnlock()

		forceKey := currentKeyRequest != forceKeySeen
		if forceKey {
			forceKeySeen = currentKeyRequest
			// A browser PLI/FIR is an explicit decoder recovery request. Discard any
			// pending P-frame and restart from the newest complete IDR.
			pending = nil
			pendingData = nil
			pendingKey = false
		}

		if forceKey || overrun {
			s.mu.RLock()
			key = s.latestKey
			cfg = append([]byte(nil), s.config...)
			s.mu.RUnlock()
			if key != nil && len(cfg) > 0 {
				next = key
				s.mu.Lock()
				if overrun {
					s.frameOverruns++
				}
				s.recoveryCount++
				s.mu.Unlock()
			}
		}

		if next == nil {
			select {
			case <-ch:
			case <-time.After(50 * time.Millisecond):
			}
			if pc.ConnectionState() == webrtc.PeerConnectionStateClosed || pc.ConnectionState() == webrtc.PeerConnectionStateFailed {
				return
			}
			continue
		}

		// First media sample must be a self-contained IDR. Never start or recover
		// a peer from a P-frame.
		if lastSeq == 0 || forceKey || overrun {
			if !((next.flags&flagKeyFrame) != 0 && len(cfg) > 0) {
				s.mu.Lock()
				s.waitedNoConfig++
				s.mu.Unlock()
				select {
				case <-ch:
				case <-time.After(50 * time.Millisecond):
				}
				continue
			}
		}

		// Configuration-only access units update the cached decoder state but are
		// never sent as standalone media samples. Advance the sequence cursor so the
		// next P-frame remains contiguous in the source stream.
		if next.flags&flagConfig != 0 && next.flags&flagKeyFrame == 0 {
			lastSeq = next.seq
			continue
		}

		data := next.data
		isKey := next.flags&flagKeyFrame != 0
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
			case <-time.After(50 * time.Millisecond):
			}
			continue
		}

		if ptsBaseUs < 0 {
			ptsBaseUs = next.ptsUs
		}
		relPtsUs := next.ptsUs - ptsBaseUs
		if relPtsUs < 0 || (lastPtsUs >= 0 && relPtsUs < lastPtsUs) {
			// The source session changed its PTS timeline. Restart the local timeline
			// without changing frame order.
			ptsBaseUs = next.ptsUs
			relPtsUs = 0
			lastPtsUs = -1
			pending = nil
			pendingData = nil
			pendingKey = false
		}

		// If this is not contiguous with the previous frame, do not send it. The
		// next IDR is the only safe recovery point for a predictive H.264 stream.
		if lastSeq != 0 && next.seq != lastSeq+1 && !isKey {
			continue
		}

		if pending != nil {
			if err := flushPending(relPtsUs, true); err != nil {
				log.Printf("WebRTC WriteSample failed: %v", err)
				_ = pc.Close()
				return
			}
		}

		pending = next
		pendingData = data
		pendingKey = isKey
		pendingRelPtsUs = relPtsUs
		lastSeq = next.seq

		if pc.ConnectionState() == webrtc.PeerConnectionStateClosed || pc.ConnectionState() == webrtc.PeerConnectionStateFailed {
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
