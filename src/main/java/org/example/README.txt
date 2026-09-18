YOGA AUTOMATION - REQUIRED ORDER

Module 1 -> Chapter 1 -> all listed parts
Module 2 -> Chapter 2 -> all listed parts
Module 3 -> Chapter 3 -> all listed parts
...

IMPORTANT SHEET2 FORMAT

A = Module number
B = Chapter number
C = Part code
D = Q1 option number
E = Q2 option number
F = Q3 option number
G = Q4 option number
H = Q5 option number

Example:

Module | Chapter | Part | Q1 | Q2 | Q3 | Q4 | Q5
1      | 1       | 1.1  | 4  | 2  | 3
1      | 1       | 1.2  | 2  | 1  | 4
1      | 1       | 1.3  | 3  | 2  | 1
1      | 1       | 1.4  | 1  | 4  | 2

2      | 2       | 2.1  | 2  | 3  | 1
2      | 2       | 2.2  | 4  | 1  | 3
2      | 2       | 2.3  | 1  | 2  | 4
2      | 2       | 2.4  | 3  | 4  | 2

Merged cells in columns A and B are supported.

Put all .java files inside:
src/main/java/org/example/

Keep credentials.json in the project root.

The code uses Java 17.
LIVE SHEET UPDATE
-----------------
The program reads the PC1 tab again every 15 seconds.
New candidate rows are picked up without restarting Main.java.
Up to 3 candidates run at the same time.

PC1 columns:
A = Username
B = Password
C = Student Name
D = Overall Status
E:N = 10 module statuses

Every module is checked against the website. Green-completed chapters are
skipped. Pending chapters are processed from their first material so stale
Sheet resume positions cannot hide unfinished work.
When all 10 module cells are Completed, column D becomes
Completed and the whole row A:N changes to red with bold white text.
