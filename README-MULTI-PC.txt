SMY MERGED - 6 PC SAFE RUNNER
================================

WHAT IS INCLUDED
1) wellness      = existing EmoEcoWellSym automation
2) certificate   = existing SMY certificate downloader/email/WhatsApp project
3) RUN_PC1.bat ... RUN_PC6.bat

HOW 6-PC MODE WORKS
- Every candidate login/email is hashed into one stable PC slot 1..6.
- PC1 only takes its candidates, PC2 only its candidates, etc.
- This prevents all 6 PCs intentionally starting the same candidate.
- The same login keeps the same PC assignment even if rows move.

FIRST TIME ON EACH PC
1) Install Java 17+ and Maven.
2) Copy this whole folder to the PC.
3) Keep credentials.json in BOTH wellness and certificate folders.
4) Check certificate/config.properties has your working mail/WhatsApp settings.
5) Run BUILD_ALL.bat once.
6) PC1 -> RUN_PC1.bat
   PC2 -> RUN_PC2.bat
   PC3 -> RUN_PC3.bat
   PC4 -> RUN_PC4.bat
   PC5 -> RUN_PC5.bat
   PC6 -> RUN_PC6.bat

PARALLEL USERS
- Default MAX_PARALLEL_USERS=1 on each PC.
- With 6 PCs that already gives up to 6 wellness browsers in parallel.
- If stable, edit each RUN_PCx.bat and set MAX_PARALLEL_USERS=2.
- 2 on each of 6 PCs means up to 12 wellness browsers total, so watch RAM and Google 429.

ERRORS
- Keep both command windows open on each PC.
- Google 429 messages remain visible and existing retry logic is preserved.
- Selenium/Chrome/email failures remain visible in the existing console logic.
- If one PC is OFF, candidates assigned to that PC wait until it returns.
  This is deliberate to prevent duplicate processing.

IMPORTANT
- Do NOT use the same RUN_PC number on two computers.
- Do NOT run RUN_PC1.bat on all six PCs.
- Use one unique PC_ID per machine.
