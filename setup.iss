; ============================================================================
; Nexira — Inno Setup 6 Script
; https://jrsoftware.org/ishelp/
;
; Build:  iscc setup.iss /DAppVersion="1.3.0"
; Silent: Nexira-Setup.exe /SILENT /NORESTART
; ============================================================================

#ifndef AppVersion
  #define AppVersion "0.0.0-dev"
#endif

; VersionInfoVersion writes into the PE header (VS_FIXEDFILEINFO), which
; Windows requires to be MAJOR.MINOR[.BUILD[.REVISION]] — digits only. Strip
; any pre-release suffix (e.g. "2.2.7-rc1" -> "2.2.7"). Mirrors the same
; normalization done in client-ui/build.gradle.kts for Compose's packageVersion.
#if Pos("-", AppVersion) > 0
  #define VersionInfo Copy(AppVersion, 1, Pos("-", AppVersion) - 1)
#else
  #define VersionInfo AppVersion
#endif

#define MyAppName      "Nexira"
#define MyAppPublisher "Hivens"
#define MyAppURL       "https://github.com/Kitty-Hivens/Nexira"
#define MyAppExeName   "Nexira.exe"
#define MyAppId        "30571060-3129-4503-b09e-716912389146"

[Setup]
; Unique ID — keeps upgrades clean (same GUID as upgradeUuid in build.gradle.kts)
AppId={{{#MyAppId}}
AppName={#MyAppName}
AppVersion={#AppVersion}
AppVerName={#MyAppName} {#AppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}/issues
AppUpdatesURL={#MyAppURL}/releases

; ── Privileges ──────────────────────────────────────────────────────────────
; No UAC prompt — installs into %LocalAppData% (machine-local) without admin rights.
; User-generated data (clients, profiles, logs) lives in %LocalAppData%\Nexira\
; (resolved by PlatformPaths via LOCALAPPDATA), so install lives one level
; deeper at %LocalAppData%\Nexira\Programs\ to avoid colliding with it.
; The dialog option lets a power user elevate if they WANT a machine-wide install.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog

; ── Paths ───────────────────────────────────────────────────────────────────
; %LOCALAPPDATA% is intentional: %AppData% (Roaming) is touched by OneDrive's
; Known Folder Move on Win11 default-OEM installs, which downgrades binaries
; in the install dir to cloud placeholders. LoadLibrary against a placeholder
; jvm.dll fails with STATUS_CLOUD_FILE_NOT_IN_SYNC (0xC0E90002), surfacing as
; "Nexira.exe - Invalid Image" on first launch after sync runs. Local AppData
; is never synced. The `Programs\` subdir matches the Slack / Discord /
; GitHub-Desktop convention for user-install Windows apps and avoids
; colliding with the data dir at %LocalAppData%\Nexira\.
DefaultDirName={localappdata}\Nexira\Programs
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes

; UsePreviousAppDir defaults to yes, which makes Inno reuse the path of a
; prior install with the same AppId regardless of DefaultDirName. That's
; the wrong behaviour for THIS upgrade: existing users sit on the broken
; %AppData%\Nexira location (the whole reason for this migration), so
; honoring their old path would leave them stuck. Force the upgrader to
; land at DefaultDirName for everyone. The InitializeSetup hook below
; runs the old uninstaller silently so we don't leave a phantom install
; behind in registry / Start Menu.
UsePreviousAppDir=no

; ── Output ──────────────────────────────────────────────────────────────────
OutputDir=.
OutputBaseFilename=Nexira-Setup
SetupIconFile=resources\icons\icon.ico

; ── Compression ─────────────────────────────────────────────────────────────
Compression=lzma2/ultra64
SolidCompression=yes
LZMAUseSeparateProcess=yes
LZMADictionarySize=1048576

; ── Appearance ──────────────────────────────────────────────────────────────
WizardStyle=modern
WizardSizePercent=110
DisableWelcomePage=no
DisableDirPage=no
DisableReadyPage=no

; ── Uninstaller ─────────────────────────────────────────────────────────────
UninstallDisplayName={#MyAppName}
UninstallDisplayIcon={app}\{#MyAppExeName}
; Do NOT auto-delete the user's game data in %LocalAppData%\Nexira
; (or the legacy %UserProfile%\.aura directory). The user can remove it manually.
CloseApplications=force
CloseApplicationsFilter=*{#MyAppExeName}*

; ── Version info embedded in installer EXE ──────────────────────────────────
VersionInfoVersion={#VersionInfo}
VersionInfoCompany={#MyAppPublisher}
VersionInfoDescription={#MyAppName} Installer
VersionInfoProductName={#MyAppName}

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "desktopicon"; \
  Description: "{cm:CreateDesktopIcon}"; \
  GroupDescription: "{cm:AdditionalIcons}"; \
  Flags: unchecked

[Files]
; Entire app directory built by :client-ui:customJpackageImage (buildSrc's
; nexira.packaging convention plugin). Replaced the Compose Desktop
; createReleaseDistributable path in B-3 so we get the same flag surface
; (--strip-debug, --vm=server, --include-locales=en,ru,de, etc.) here
; that the AppImage path on Linux already has. Layout is identical from
; Inno's perspective: Nexira\ subdir with bin\Nexira.exe,
; lib\runtime\, lib\app\, so the trailing Nexira\* glob and the
; {app}\{#MyAppExeName} references below stay correct.
Source: "client-ui\build\customJpackageImage\Nexira\*"; \
  DestDir: "{app}"; \
  Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; Start Menu
Name: "{autoprograms}\{#MyAppName}"; \
  Filename: "{app}\{#MyAppExeName}"

; Desktop (optional, controlled by task above)
Name: "{autodesktop}\{#MyAppName}"; \
  Filename: "{app}\{#MyAppExeName}"; \
  Tasks: desktopicon

[Run]
; "Launch after install" checkbox on the final wizard page
Filename: "{app}\{#MyAppExeName}"; \
  Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; \
  Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Remove the install directory if empty after uninstall
; (user data lives in %LocalAppData%\Nexira, NOT touched here)
Type: dirifempty; Name: "{app}"

[Code]
// Detect a prior install (regardless of where it lives -- Roaming AppData
// from pre-{#AppVersion} builds, or LocalAppData\Nexira\Programs from a
// previous run of this installer) and run its silent uninstaller before
// we lay down the new files.
//
// Necessary because UsePreviousAppDir=no above tells Inno to ignore the
// prior install's registered directory, which means the OLD uninstall
// entry would otherwise stay in Add/Remove Programs forever, pointing at
// an install we just orphaned. Running the prior uninstaller here cleans
// it up before we write our own.
//
// `Exec` waits for the uninstaller to terminate (`ewWaitUntilTerminated`)
// so the new install does not race the old one's file deletes. Failure
// is non-fatal: we log and proceed -- a stranded uninstall entry is bad
// UX but not worse than refusing to install.
function GetPriorUninstallerCmd(): String;
var
  s: String;
begin
  Result := '';
  if RegQueryStringValue(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{{#MyAppId}}_is1', 'QuietUninstallString', s) then
    Result := s
  else if RegQueryStringValue(HKLM, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{{#MyAppId}}_is1', 'QuietUninstallString', s) then
    Result := s;
end;

function InitializeSetup(): Boolean;
var
  cmd: String;
  exe: String;
  args: String;
  spacePos: Integer;
  exitCode: Integer;
begin
  Result := True;
  cmd := GetPriorUninstallerCmd();
  if cmd = '' then Exit;

  Log('Prior install found, running silent uninstaller: ' + cmd);

  // QuietUninstallString is `"<exe>" /VERYSILENT` (Inno's own format).
  // Split on the first space after the quoted path so we can pass args
  // to Exec separately (Exec wants exe + params, not a single command line).
  if (Length(cmd) > 0) and (cmd[1] = '"') then begin
    spacePos := Pos('" ', cmd);
    if spacePos > 0 then begin
      exe  := Copy(cmd, 2, spacePos - 2);
      args := Copy(cmd, spacePos + 2, Length(cmd) - spacePos - 1);
    end else begin
      exe  := Copy(cmd, 2, Length(cmd) - 2);
      args := '';
    end;
  end else begin
    spacePos := Pos(' ', cmd);
    if spacePos > 0 then begin
      exe  := Copy(cmd, 1, spacePos - 1);
      args := Copy(cmd, spacePos + 1, Length(cmd) - spacePos);
    end else begin
      exe  := cmd;
      args := '';
    end;
  end;

  // /NORESTART is added unconditionally even if QuietUninstallString
  // already implies it -- defends against future Inno versions that
  // shape the command differently.
  if Pos('/NORESTART', UpperCase(args)) = 0 then begin
    if args <> '' then args := args + ' ';
    args := args + '/NORESTART';
  end;

  if not Exec(exe, args, '', SW_HIDE, ewWaitUntilTerminated, exitCode) then begin
    Log('Prior uninstaller failed to launch; continuing anyway.');
  end else if exitCode <> 0 then begin
    Log('Prior uninstaller exited with code ' + IntToStr(exitCode) + '; continuing.');
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then begin
    // Nothing extra needed — CloseApplications=force handles process shutdown
  end;
end;
