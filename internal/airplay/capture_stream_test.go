package airplay

import (
	"errors"
	"io"
	"testing"
	"time"
)

func TestStreamSourceShedsBeforeStart(t *testing.T) {
	s := NewStreamSource()
	pts := time.Now()
	for i := 0; i < streamSourceQueue+10; i++ {
		if err := s.Push([]byte{byte(i)}, pts); err != nil {
			t.Fatalf("push %d before start: %v", i, err)
		}
	}
	au, _ := s.ReadVideoAccessUnit()
	if au.AnnexB[0] != 10 { // oldest 10 were shed
		t.Fatalf("first surviving frame = %d, want 10", au.AnnexB[0])
	}
}

func TestStreamSource(t *testing.T) {
	s := NewStreamSource()
	pts := time.Now()
	if err := s.Push([]byte{0, 0, 0, 1, 0x65}, pts); err != nil {
		t.Fatal(err)
	}
	au, err := s.AsCapture().ReadVideoAccessUnit()
	if err != nil || len(au.AnnexB) != 5 || !au.PTS.Equal(pts) {
		t.Fatalf("got %v, %v", au, err)
	}
	for i := 0; i < streamSourceQueue; i++ {
		_ = s.Push([]byte{1}, pts)
	}
	if err := s.Push([]byte{1}, pts); !errors.Is(err, errStreamSourceBacklog) {
		t.Fatalf("overflow err = %v", err)
	}
	s.Close()
	if err := s.Push([]byte{1}, pts); !errors.Is(err, io.ErrClosedPipe) {
		t.Fatalf("closed err = %v", err)
	}
}
