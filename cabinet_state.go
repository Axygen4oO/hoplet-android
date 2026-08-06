package main

import "sync"

type CabinetState struct {
	Mode                 string
	Email                string
	Password             string
	SubscriptionPassword string
}

var (
	cabinetStateMu sync.Mutex
	cabinetStates  = make(map[int64]*CabinetState)
)

func cabinetGetState(userID int64) CabinetState {
	cabinetStateMu.Lock()
	defer cabinetStateMu.Unlock()

	state := cabinetStates[userID]
	if state == nil {
		return CabinetState{}
	}

	return *state
}

func cabinetSetState(userID int64, state CabinetState) {
	cabinetStateMu.Lock()
	defer cabinetStateMu.Unlock()

	copyState := state
	cabinetStates[userID] = &copyState
}

func cabinetClearState(userID int64) {
	cabinetStateMu.Lock()
	defer cabinetStateMu.Unlock()

	delete(cabinetStates, userID)
}
