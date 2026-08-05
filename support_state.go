package main

import "sync"

type SupportSessionState struct {
	Mode             string
	TicketID         string
	SelectedTicketID string
}

var (
	supportStateMu sync.Mutex
	supportStates  = make(map[int64]SupportSessionState)
)

func supportGetState(telegramID int64) SupportSessionState {
	supportStateMu.Lock()
	defer supportStateMu.Unlock()

	return supportStates[telegramID]
}

func supportSetState(telegramID int64, state SupportSessionState) {
	supportStateMu.Lock()
	defer supportStateMu.Unlock()

	if state.Mode == "" && state.TicketID == "" && state.SelectedTicketID == "" {
		delete(supportStates, telegramID)
		return
	}

	supportStates[telegramID] = state
}

func supportUpdateState(telegramID int64, update func(*SupportSessionState)) {
	supportStateMu.Lock()
	defer supportStateMu.Unlock()

	state := supportStates[telegramID]
	update(&state)
	if state.Mode == "" && state.TicketID == "" && state.SelectedTicketID == "" {
		delete(supportStates, telegramID)
		return
	}
	supportStates[telegramID] = state
}

func supportClearState(telegramID int64) {
	supportStateMu.Lock()
	defer supportStateMu.Unlock()
	delete(supportStates, telegramID)
}

func supportClearMode(telegramID int64) {
	supportUpdateState(telegramID, func(state *SupportSessionState) {
		state.Mode = ""
		state.TicketID = ""
	})
}
