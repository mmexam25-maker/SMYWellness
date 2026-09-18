SMY MERGED - 11 PC RUNNER
=========================

Spreadsheet: 1YCWNaiJENZuNTPfDd0-w6DYOSkLifehy1y0CX-9YSc4

WELLNESS
- PC1 reads tab PC1, PC2 reads tab PC2 ... PC11 reads tab PC11.
- MAX_PARALLEL_USERS is per computer. Start at 1.
- Google Sheets 429 uses backoff/retry.
- Column U remains Aadhaar. If U is blank, the program does NOT invent a fake/duplicate ID; Aadhaar is skipped and photo upload continues if the site allows it.
- Column V is Profile/Photo status. DG/INDoS/login failures are written there concisely, e.g. DG ERROR - INDoS/Login not working.
- When wellness is completed, candidate details are queued into Download certificate.

CERTIFICATE
- All 11 PCs may watch the same Download certificate tab. Stable login sharding assigns each candidate to one PC.
- Timeout errors are shortened in the sheet and automatically retried.
- After certificate email + WhatsApp completes, the row moves to Completed.
- Completed column J contains Sent Date & Time.

RUN
1. Build once with BUILD_ALL.bat.
2. Use a UNIQUE runner on each machine: RUN_PC1.bat ... RUN_PC11.bat.
3. Never use the same PC_ID on two machines.
4. For more users on one PC, edit only MAX_PARALLEL_USERS in that PC's BAT file.
