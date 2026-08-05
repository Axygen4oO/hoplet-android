package main

type TelegramState struct {
	WaitingForDevices bool
	WaitingForPorts   bool
	WaitingForLabel   bool
	WaitingForPlan    bool

	WaitingExtendDays bool
	WaitingSetDays    bool

	TargetPassword string

	TempMaxDevs int
	TempPorts   string
	TempLabel   string
	TempPlan    string

	WizardDays    int
	WizardDevices int
}

var tgState TelegramState
