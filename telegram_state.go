package main

type TelegramState struct {
	WaitingForDevices bool
	WaitingForPorts   bool
	WaitingForLabel   bool
	WaitingForPlan    bool

	WaitingExtendDays     bool
	WaitingSetDays        bool
	WaitingUserExtendDays bool
	WaitingUserMessage    bool
	WaitingBulkExtendDays bool

	TargetPassword           string
	TargetUserSubscriptionID string

	TempMaxDevs int
	TempPorts   string
	TempLabel   string
	TempPlan    string

	WizardDays    int
	WizardDevices int

	BulkExtendMessageID      int
	BulkExtendDays           int64
	BulkExtendIncludeActive  bool
	BulkExtendIncludeBlocked bool
	BulkExtendIncludeExpired bool

	NotificationStage                      string
	NotificationTitle                      string
	NotificationPreview                    string
	NotificationIgnoreNextDuplicateMessage bool
}

var tgState TelegramState
