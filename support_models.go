package main

type SupportTicket struct {
	ID                         string            `json:"id"`
	OwnerEmail                 string            `json:"owner_email"`
	OwnerTelegramID            int64             `json:"owner_telegram_id"`
	OwnerName                  string            `json:"owner_name"`
	OwnerUsername              string            `json:"owner_username,omitempty"`
	CreatedAt                  int64             `json:"created_at"`
	LastMessageAt              int64             `json:"last_message_at"`
	Status                     string            `json:"status"`
	Preview                    string            `json:"preview,omitempty"`
	LockedByTelegramID         int64             `json:"locked_by_telegram_id,omitempty"`
	LockedByEmail              string            `json:"locked_by_email,omitempty"`
	LockedByName               string            `json:"locked_by_name,omitempty"`
	LockedAt                   int64             `json:"locked_at,omitempty"`
	AssignedOperatorTelegramID int64             `json:"assigned_operator_telegram_id,omitempty"`
	AssignedOperatorEmail      string            `json:"assigned_operator_email,omitempty"`
	AssignedOperatorName       string            `json:"assigned_operator_name,omitempty"`
	ClosedAt                   int64             `json:"closed_at,omitempty"`
	Messages                   []*SupportMessage `json:"messages"`
	AIHint                     *SupportAIHint    `json:"ai_hint,omitempty"`
}

type SupportMessage struct {
	ID               string              `json:"id"`
	CreatedAt        int64               `json:"created_at"`
	SenderRole       string              `json:"sender_role"`
	SenderEmail      string              `json:"sender_email,omitempty"`
	SenderTelegramID int64               `json:"sender_telegram_id,omitempty"`
	SenderName       string              `json:"sender_name,omitempty"`
	SenderUsername   string              `json:"sender_username,omitempty"`
	Text             string              `json:"text,omitempty"`
	Attachments      []SupportAttachment `json:"attachments,omitempty"`
}

type SupportAttachment struct {
	Type         string `json:"type"`
	FileID       string `json:"file_id"`
	FileUniqueID string `json:"file_unique_id,omitempty"`
	FileName     string `json:"file_name,omitempty"`
	MimeType     string `json:"mime_type,omitempty"`
	FileSize     int64  `json:"file_size,omitempty"`
	Width        int    `json:"width,omitempty"`
	Height       int    `json:"height,omitempty"`
	Duration     int    `json:"duration,omitempty"`
}

type SupportAIHint struct {
	SuggestedReply   string   `json:"suggested_reply,omitempty"`
	PossibleSolution string   `json:"possible_solution,omitempty"`
	SimilarTicketIDs []string `json:"similar_ticket_ids,omitempty"`
	Provider         string   `json:"provider,omitempty"`
	LastAnalyzedAt   int64    `json:"last_analyzed_at,omitempty"`
}

type supportIdentity struct {
	Email      string
	Name       string
	Username   string
	TelegramID int64
	Role       string
}

type supportTicketSnapshot struct {
	Ticket *SupportTicket
	User   *UserAccount
	Entry  *PasswordEntry
	Orders []*Order
}

const (
	supportStatusNew         = "new"
	supportStatusInProgress  = "in_progress"
	supportStatusWaitingUser = "waiting_user"
	supportStatusClosed      = "closed"
	supportSenderUser        = "user"
	supportSenderOperator    = "operator"
	supportSenderSystem      = "system"
)

func supportStatusLabel(status string) string {
	switch status {
	case supportStatusNew:
		return "Новый"
	case supportStatusInProgress:
		return "В работе"
	case supportStatusWaitingUser:
		return "Ожидание пользователя"
	case supportStatusClosed:
		return "Закрыт"
	default:
		return status
	}
}

func supportStatusEmoji(status string) string {
	switch status {
	case supportStatusNew:
		return "🆕"
	case supportStatusInProgress:
		return "🛠"
	case supportStatusWaitingUser:
		return "⏳"
	case supportStatusClosed:
		return "✅"
	default:
		return "💬"
	}
}

func supportStatusCode(status string) string {
	switch status {
	case supportStatusNew:
		return "new"
	case supportStatusInProgress:
		return "work"
	case supportStatusWaitingUser:
		return "wait"
	case supportStatusClosed:
		return "closed"
	default:
		return "new"
	}
}

func supportStatusFromCode(code string) string {
	switch code {
	case "new":
		return supportStatusNew
	case "work":
		return supportStatusInProgress
	case "wait":
		return supportStatusWaitingUser
	case "closed":
		return supportStatusClosed
	default:
		return supportStatusNew
	}
}
