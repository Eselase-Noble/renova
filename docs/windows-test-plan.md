# Testing the .NET targets on Windows

Renova's .NET work was built and checked on Linux. That proves less than it should in four places, and each
needs a Windows machine:

| What | Checked on Linux | Not checked until Windows |
|---|---|---|
| The .NET Framework test applications themselves | Their migrated versions build and pass | That the originals build and pass: their project format needs Visual Studio's MSBuild |
| Windows Forms (C#, Visual Basic) and WPF | The migrated projects compile, with the Windows targeting pack | Their tests, and that the applications start and work |
| ASP.NET MVC 5 → ASP.NET Core | The migrated application builds, starts and answers | That it answers as the original does: the original needs IIS |
| Renova itself on Windows | Nothing: CI runs on Linux only | That the CLI, the .NET plugin and the desktop app work there |

This plan takes about an hour. Stop at the first step that fails and send back what the last section lists:
later steps depend on earlier ones.

## What you need on the Windows machine

- **Java 21** (`java -version`) and **Git** (`git --version`): Renova keeps every migration stage as a commit.
- **.NET SDK 10** (`dotnet --list-sdks`); also **.NET SDK 8** for one entry of step 2.
- **Visual Studio 2022** (or the Build Tools) with the workloads **.NET desktop development** and **ASP.NET and
  web development**, and the **.NET Framework 4.7.2 and 4.8 targeting packs** (Individual components).
- **IIS Express**, which the web workload installs (`C:\Program Files\IIS Express\iisexpress.exe`).
- **The kit**: `renova-windows-kit.zip`, unpacked to `C:\renova-kit`. It holds `renova.jar`, the test
  applications (`apps`), the suites and scripts (`benchmark`), and this file.

Every command below is run in PowerShell from `C:\renova-kit`. If scripts are blocked, run
`Set-ExecutionPolicy -Scope Process Bypass` first.

## 1. The originals build and pass (10 minutes)

```powershell
.\benchmark\windows\Build-Originals.ps1 -Apps .\apps
```

**Expected:** a table with `Build = pass` for all six applications, and `Tests = pass` for `billing-netfx48`,
`ledger-vbnet472`, `shop-mvc5`, `desk-winforms-net472` and `notes-wpf-net48` (`till-vb-winforms-net48` has no
tests).

**Why it matters:** these applications were written by hand, on a machine that cannot build them. If one does
not build, the fault is in the test application and its migration result means less. Tell me which and I will
fix the application, not the tool.

**Least certain:** whether the script finds the NUnit adapter for the two NUnit test projects. If `Tests`
says FAIL with "no test is available", the applications are fine and the script is wrong.

## 2. Renova on Windows, on what already passes on Linux (10 minutes)

```powershell
java -jar renova.jar benchmark --suite benchmark\dotnet.yaml --apps .\apps --out C:\renova-out\dotnet --configs deterministic
```

**Expected:** five rows, each `PASSES` with all checks (6/6, 5/5, 3/3, 2/2, 6/6). The `aspnetcore31-to-8` row
needs .NET SDK 8.

**Why it matters:** the same suite passes on Linux. A failure here is Renova on Windows (paths, line endings,
finding `dotnet.exe`), not .NET.

## 3. Windows Forms and WPF, with their tests (10 minutes)

```powershell
java -jar renova.jar benchmark --suite benchmark\dotnet-windows.yaml --apps .\apps --out C:\renova-out\windows --configs deterministic
```

**Expected:** three rows, each `PASSES` (5/5, 4/4, 3/3). On Windows the tests are run: open
`C:\renova-out\windows\deterministic\winforms-csharp-to-10\.renova\report.md` and look under *Verification*
for "4 test(s) ran and passed"; for `wpf-csharp-to-10`, "2 test(s) ran and passed". The note about the
Windows targeting pack, which Linux prints, must not be there.

## 4. The desktop applications start and work (15 minutes)

Start each migrated application and try what is listed. They are small on purpose.

```powershell
dotnet run --project C:\renova-out\windows\deterministic\winforms-csharp-to-10\Desk
```

- The window's title is **Split the bill** (it comes from a resource file).
- **Tip %** is filled in with **12.5** (it comes from `App.config`; your regional settings may show `12,5`).
- Bill `81`, people `3`, **Split** shows **30.38 each**.
- Bill `abc`, **Split** shows a warning, "Enter the bill and the tip as numbers."

```powershell
dotnet run --project C:\renova-out\windows\deterministic\wpf-csharp-to-10\Notes
```

- The title is **Notes**. **Add** and **Clear** are greyed out while the box and the list are empty.
- Type `buy cocoa`, **Add**: it appears in the list, the box empties, the title becomes **Notes (1)**.
- **Clear** empties the list and the title is **Notes** again.

```powershell
dotnet run --project C:\renova-out\windows\deterministic\winforms-vb-to-10\Till
```

- A window titled **Till** opens. No console window opens with it.
- `2.50`, **Ring**, `4`, **Ring** shows **2 item(s): 6.50**. **Void** shows **0 item(s): 0.00**.
- `abc`, **Ring** shows a warning, "Enter a price.", whose title is **Acme.Till**.

**Least certain:** the Visual Basic application. It starts through Visual Basic's application framework
(`My.Application`, no `Sub Main`), which compiles here and has never been run.

## 5. ASP.NET MVC 5 against ASP.NET Core (15 minutes, uses your AI key)

Migrate the MVC application. This sends the web project to the AI provider: about 25,000 tokens.

```powershell
$env:ANTHROPIC_API_KEY = "<your key>"
java -jar renova.jar migrate .\apps\shop-mvc5 --out C:\renova-out\shop --ai anthropic --max-ai-iterations 5
```

**Expected:** the last lines say `build      PASSES` and `3 test(s) ran and passed`. AI output varies between
runs; if the build fails, send `C:\renova-out\shop\.renova\report.md`.

Then run the original and the result side by side:

```powershell
.\benchmark\windows\Compare-AspNet.ps1 -Original .\apps\shop-mvc5 -Migrated C:\renova-out\shop -WebProject Shop.Web
```

**Expected:** eleven rows. Status codes should match on every row: 200 for the pages, the API and the
stylesheet, 404 for `/Products/Details/99` and `/api/stock/99`, 400 for `/Products/Details`.

**What may differ, and is worth knowing rather than a failure:**

- `/api/stock` and `/api/stock/3`: Web API 2 answers a client that names no format with JSON; property names
  and order should match. If the original answers XML, say so.
- The pages: white space is ignored, but ASP.NET Core writes some HTML differently from MVC 5 (attribute
  order on form fields, `data-val` attributes). The script prints where the first difference is.
- Links on `/category/food`: both versions build links from the current route, and this is the place where
  MVC 5 and ASP.NET Core are most likely to disagree.

The script writes `aspnet-comparison.csv` to the current folder.

## 6. The desktop app (optional, 5 minutes)

Renova's own desktop application is built from the repository, not from the kit:

```powershell
git clone <the repository>; cd renova; git checkout feature/dotnet-ecosystem
mvn -pl desktop -am install -DskipTests
java -jar desktop\target\renova-desktop-0.1.0-SNAPSHOT.jar
```

Open `C:\renova-kit\apps\billing-netfx48` as a project. **Expected:** the target is **.NET → 10**, the plan
shows four automated steps, and a migration ends with a passing build and "7 test(s) ran and passed".

## What to send back

For each step: passed, or the output of the command. For any failure, also:

- step 1: the lines MSBuild printed for the application that failed;
- steps 2 and 3: `results.md` from the output folder, and `.renova\report.md` from the row that failed;
- step 4: what you saw in place of what is listed;
- step 5: `aspnet-comparison.csv` and what the script printed below the table.
