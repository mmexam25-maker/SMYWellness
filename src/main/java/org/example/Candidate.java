package org.example;

/**
 * One row from the "Download certificate" sheet.
 *
 * A Date
 * B Login ID / recipient email
 * C Password
 * D Candidate name (filled from SMY Profile when blank)
 * E Candidate WhatsApp/mobile (filled from SMY Profile when blank)
 * F Source status (must be Completed for new email work)
 * H Live process status
 * I Mail status (SENT / FAILED; SENT means never resend email)
 * J WhatsApp status
 * L Optional agent name - receives an additional WhatsApp using config mapping
 */
public record Candidate(
        int rowNumber,
        String loginId,
        String password,
        String candidateName,
        String mobileNumber,
        String whatsappStatus,
        String processStatus,
        String mailStatus,
        String agentName
) {}
