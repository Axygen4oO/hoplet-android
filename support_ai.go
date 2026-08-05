package main

import (
	"sort"
	"strings"
	"time"
)

type SupportAdvisor interface {
	Analyze(ticket *SupportTicket, related []*SupportTicket) *SupportAIHint
}

var supportAdvisor SupportAdvisor = supportRuleBasedAdvisor{}

type supportRuleBasedAdvisor struct{}

func (supportRuleBasedAdvisor) Analyze(ticket *SupportTicket, related []*SupportTicket) *SupportAIHint {
	if ticket == nil {
		return nil
	}

	last := supportLatestUserMessage(ticket)
	if last == nil {
		return &SupportAIHint{
			Provider:       "rule-based",
			LastAnalyzedAt: time.Now().Unix(),
		}
	}

	text := strings.ToLower(strings.TrimSpace(last.Text))
	templateKey := ""
	switch {
	case strings.Contains(text, "оплат"), strings.Contains(text, "платеж"), strings.Contains(text, "чек"):
		templateKey = "payment"
	case strings.Contains(text, "подпис"), strings.Contains(text, "продл"), strings.Contains(text, "тариф"):
		templateKey = "subscription"
	case strings.Contains(text, "устрой"), strings.Contains(text, "device"):
		templateKey = "devices"
	case strings.Contains(text, "обнов"), strings.Contains(text, "update"), strings.Contains(text, "верс"):
		templateKey = "update"
	}

	hint := &SupportAIHint{
		Provider:       "rule-based",
		LastAnalyzedAt: time.Now().Unix(),
	}

	if templateKey != "" {
		if template, ok := supportQuickReplyTemplate(templateKey); ok {
			hint.SuggestedReply = template
		}
		hint.PossibleSolution = "Похоже, сообщение относится к типовой категории. Можно начать с шаблонного ответа и затем уточнить детали по аккаунту."
	} else {
		hint.PossibleSolution = "Сообщение не попало в типовую категорию. Проверьте историю заказов, подписку и вложения пользователя."
	}

	similar := make([]string, 0, 3)
	seen := make(map[string]struct{})
	for _, other := range related {
		if other == nil || other.ID == ticket.ID {
			continue
		}
		if other.OwnerEmail == ticket.OwnerEmail || supportTicketsShareKeywords(ticket, other) {
			if _, exists := seen[other.ID]; exists {
				continue
			}
			seen[other.ID] = struct{}{}
			similar = append(similar, other.ID)
		}
	}

	sort.Strings(similar)
	if len(similar) > 3 {
		similar = similar[:3]
	}
	hint.SimilarTicketIDs = similar

	return hint
}

func supportLatestUserMessage(ticket *SupportTicket) *SupportMessage {
	if ticket == nil {
		return nil
	}
	for i := len(ticket.Messages) - 1; i >= 0; i-- {
		msg := ticket.Messages[i]
		if msg != nil && msg.SenderRole == supportSenderUser {
			return msg
		}
	}
	return nil
}

func supportTicketsShareKeywords(a, b *SupportTicket) bool {
	if a == nil || b == nil {
		return false
	}

	aText := strings.ToLower(strings.TrimSpace(a.Preview))
	bText := strings.ToLower(strings.TrimSpace(b.Preview))
	if aText == "" || bText == "" {
		return false
	}

	keywords := []string{
		"оплат",
		"подпис",
		"устрой",
		"обнов",
		"платеж",
		"тариф",
		"device",
		"update",
	}

	for _, keyword := range keywords {
		if strings.Contains(aText, keyword) && strings.Contains(bText, keyword) {
			return true
		}
	}

	return false
}
