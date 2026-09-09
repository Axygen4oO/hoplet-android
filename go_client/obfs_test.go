package main

import (
	"bytes"
	"testing"
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
	if audio.PayloadType != 111 || audio.PaddingMax != 24 {
		t.Fatalf("unexpected audio config: %+v", audio)
	}

	video := NewObfsConfig("video")
	if video.PayloadType != 96 || video.PaddingMax != 60 {
		t.Fatalf("unexpected video config: %+v", video)
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
		if !obfsIsRTPPacket(wire) {
			t.Fatalf("%s packet was not recognized as RTP-obfs", tc.mode)
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
