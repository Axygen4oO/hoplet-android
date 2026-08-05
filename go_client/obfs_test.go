package main

import (
	"bytes"
	"testing"
	"time"
)

func TestNormalizeObfsMode(t *testing.T) {
	if got := normalizeObfsMode("video"); got != "video" {
		t.Fatalf("normalizeObfsMode(video) = %q, want video", got)
	}
	if got := normalizeObfsMode(" VIDEO "); got != "video" {
		t.Fatalf("normalizeObfsMode(VIDEO) = %q, want video", got)
	}
	if got := normalizeObfsMode("unknown"); got != "audio" {
		t.Fatalf("normalizeObfsMode(unknown) = %q, want audio", got)
	}
}

func TestNewObfsConfigProfiles(t *testing.T) {
	audio := NewObfsConfig("audio")
	if audio.Mode != "audio" || audio.PayloadType != 111 || audio.ClockRate != 48000 {
		t.Fatalf("unexpected audio config: %+v", audio)
	}

	video := NewObfsConfig("video")
	if video.Mode != "video" || video.PayloadType != 96 || video.ClockRate != 90000 {
		t.Fatalf("unexpected video config: %+v", video)
	}
	if video.FramePacketsMin <= 1 || video.FramePacketsMax < video.FramePacketsMin {
		t.Fatalf("video frame packet range looks invalid: %+v", video)
	}
}

func TestObfsWrapRoundTripForAudioAndVideo(t *testing.T) {
	key := bytes.Repeat([]byte{0x42}, wrapKeyLen)
	payload := []byte("dtls-payload-for-obfs-roundtrip")

	tests := []struct {
		mode        string
		payloadType byte
	}{
		{mode: "audio", payloadType: 111},
		{mode: "video", payloadType: 96},
	}

	for _, tc := range tests {
		cfg := NewObfsConfig(tc.mode)
		state := NewObfsState()

		wire, err := obfsWrapPacket(key, payload, cfg, state)
		if err != nil {
			t.Fatalf("%s wrap failed: %v", tc.mode, err)
		}
		if got := wire[1] & 0x7F; got != tc.payloadType {
			t.Fatalf("%s payload type = %d, want %d", tc.mode, got, tc.payloadType)
		}

		dst := make([]byte, len(payload))
		n, err := obfsUnwrapPacket(key, wire, dst)
		if err != nil {
			t.Fatalf("%s unwrap failed: %v", tc.mode, err)
		}
		if got := dst[:n]; !bytes.Equal(got, payload) {
			t.Fatalf("%s payload mismatch: got %q want %q", tc.mode, got, payload)
		}
	}
}

func TestVideoStateKeepsTimestampInsideFrame(t *testing.T) {
	cfg := NewObfsConfig("video")
	cfg.TimestampMin = 3000
	cfg.TimestampMax = 3000
	cfg.TimestampQuantum = 3000
	cfg.FramePacketsMin = 2
	cfg.FramePacketsMax = 2

	state := &ObfsState{
		seq:       100,
		timestamp: 9000,
	}

	start := time.Unix(0, 0)

	seq1, ts1, marker1 := state.nextHeader(cfg, start, 1, 1, 0)
	seq2, ts2, marker2 := state.nextHeader(cfg, start.Add(5*time.Millisecond), 1, 1, 0)
	seq3, ts3, marker3 := state.nextHeader(cfg, start.Add(40*time.Millisecond), 1, 1, 0)

	if seq1 != 100 || ts1 != 9000 || marker1 {
		t.Fatalf("first video packet = (%d,%d,%t), want (100,9000,false)", seq1, ts1, marker1)
	}
	if seq2 != 101 || ts2 != 9000 || !marker2 {
		t.Fatalf("second video packet = (%d,%d,%t), want (101,9000,true)", seq2, ts2, marker2)
	}
	if seq3 != 102 || ts3 != 12000 || marker3 {
		t.Fatalf("third video packet = (%d,%d,%t), want (102,12000,false)", seq3, ts3, marker3)
	}
}

func TestAudioStateAdvancesTimestampPerPacket(t *testing.T) {
	cfg := NewObfsConfig("audio")
	cfg.TimestampMin = 120
	cfg.TimestampMax = 120
	cfg.TimestampQuantum = 120

	state := &ObfsState{
		seq:       7,
		timestamp: 1000,
	}

	start := time.Unix(0, 0)

	seq1, ts1, marker1 := state.nextHeader(cfg, start, 1, 1, 0)
	seq2, ts2, marker2 := state.nextHeader(cfg, start.Add(5*time.Millisecond), 1, 1, 0)

	if seq1 != 7 || ts1 != 1000 || marker1 {
		t.Fatalf("first audio packet = (%d,%d,%t), want (7,1000,false)", seq1, ts1, marker1)
	}
	if seq2 != 8 || ts2 != 1120 || marker2 {
		t.Fatalf("second audio packet = (%d,%d,%t), want (8,1120,false)", seq2, ts2, marker2)
	}
}
