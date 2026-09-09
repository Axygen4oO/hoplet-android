package main

import "testing"

func TestBuildTurnParamsRawTunDoesNotForceNoTLS(t *testing.T) {
	tp := buildTurnParams("", "", nil, nil, "audio", false, true, false)
	if tp.NoTLS {
		t.Fatal("rawtun must not force NoTLS")
	}
	if !tp.RawMode {
		t.Fatal("rawtun must keep RawMode enabled")
	}
}

func TestBuildTurnParamsExplicitNoTLSStillWorks(t *testing.T) {
	tp := buildTurnParams("", "", nil, nil, "audio", true, true, false)
	if !tp.NoTLS {
		t.Fatal("explicit NoTLS must stay enabled")
	}
}
