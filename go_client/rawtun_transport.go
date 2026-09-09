package main

func buildTurnParams(host, port string, hashes []string, wrapKey []byte, obfsMode string, noTLS bool, rawMode bool, turnTCP bool) *TurnParams {
	return &TurnParams{
		Host:         host,
		Port:         port,
		Hashes:       hashes,
		WrapKey:      wrapKey,
		ObfsMode:     obfsMode,
		NoTLS:        noTLS,
		RawMode:      rawMode,
		TCPTransport: turnTCP,
	}
}
