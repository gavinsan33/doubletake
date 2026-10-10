package airplay

import (
	"errors"
	"io"
	"sync"
	"sync/atomic"
	"time"
)

// streamSourceQueue bounds buffered access units (~4 s at 30 fps). Encoded
// reference pictures cannot be dropped, so overflow fails the stream.
const streamSourceQueue = 120

var errStreamSourceBacklog = errors.New("stream source backlog limit exceeded")

// StreamSource is a push-fed capture for platforms where the encoder lives
// outside this process (e.g. Android MediaCodec). The producer pushes complete
// Annex-B access units; AsCapture feeds them to MirrorSession.StreamFrames.
type StreamSource struct {
	ch   chan VideoAccessUnit
	done chan struct{}
	once sync.Once
	// started is set by the first read. Until then nothing can be sent, and
	// frames before the first IDR are discarded anyway, so a full queue (the
	// encoder runs during the rest of SETUP) sheds old frames instead of failing.
	started atomic.Bool
}

func NewStreamSource() *StreamSource {
	return &StreamSource{ch: make(chan VideoAccessUnit, streamSourceQueue), done: make(chan struct{})}
}

// Push queues one access unit (4-byte start codes, SPS/PPS ahead of each IDR).
func (s *StreamSource) Push(annexB []byte, pts time.Time) error {
	select {
	case <-s.done:
		return io.ErrClosedPipe
	default:
	}
	au := VideoAccessUnit{AnnexB: annexB, PTS: pts}
	for {
		select {
		case s.ch <- au:
			return nil
		default:
		}
		if s.started.Load() {
			return errStreamSourceBacklog
		}
		select { // not streaming yet: drop the oldest
		case <-s.ch:
		default:
		}
	}
}

func (s *StreamSource) Close() { s.once.Do(func() { close(s.done) }) }

func (s *StreamSource) ReadVideoAccessUnit() (VideoAccessUnit, error) {
	s.started.Store(true)
	select {
	case au := <-s.ch:
		return au, nil
	case <-s.done:
		return VideoAccessUnit{}, io.EOF
	}
}

type streamSourceCloser struct{ s *StreamSource }

func (streamSourceCloser) Read([]byte) (int, error) { return 0, io.EOF }
func (c streamSourceCloser) Close() error           { c.s.Close(); return nil }

func (s *StreamSource) AsCapture() *ScreenCapture {
	return &ScreenCapture{stdout: streamSourceCloser{s}, frames: s, waitCh: s.done, streamOnly: true}
}
