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

	// Push admin wizard state. Values are short-lived and never persisted.
	PushStage          string
	PushType           string
	PushTitle          string
	PushMessage        string
	PushDeepLink       string
	PushAudience       string
	PushTargetEmail    string
	PushPreview        string
	PushMode           string
	PushTargetIdentity string
	PushTargetDevices  []string
	PushSending        bool
}

var tgState TelegramState
