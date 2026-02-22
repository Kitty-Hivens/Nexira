; ============================================================================
; Aura Launcher — Inno Setup 6 Script
; https://jrsoftware.org/ishelp/
;
; Build:  iscc setup.iss /DAppVersion="1.3.0"
; Silent: AuraLauncher-Setup.exe /SILENT /NORESTART
; ============================================================================

#ifndef AppVersion
  #define AppVersion "0.0.0-dev"
#endif

#define MyAppName      "Aura Launcher"
#define MyAppPublisher "Hivens"
#define MyAppURL       "https://github.com/Kitty-Hivens/Aura-Launcher"
#define MyAppExeName   "AuraLauncher.exe"
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
; No UAC prompt — installs into %AppData% without admin rights.
; The dialog option lets a power user elevate if they WANT a machine-wide install.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog

; ── Paths ───────────────────────────────────────────────────────────────────
DefaultDirName={userappdata}\AuraLauncher
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes

; ── Output ──────────────────────────────────────────────────────────────────
OutputDir=.
OutputBaseFilename=AuraLauncher-Setup
SetupIconFile=client-ui\src\commonMain\composeResources\drawable\icon.ico

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
; Do NOT auto-delete the user's game data in %AppData%\.aura
; The user can remove it manually if needed.
CloseApplications=force
CloseApplicationsFilter=*{#MyAppExeName}*

; ── Version info embedded in installer EXE ──────────────────────────────────
VersionInfoVersion={#AppVersion}
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
; Entire app directory built by :client-ui:createReleaseDistributable
; Добавлено AuraLauncher\* чтобы избежать вложенности папок AuraLauncher\AuraLauncher
Source: "client-ui\build\compose\binaries\main-release\app\AuraLauncher\*"; \
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
; (user data lives in %AppData%\.aura, NOT touched here)
Type: dirifempty; Name: "{app}"

[Code]
// Show a friendly message when upgrading from an older install
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then begin
    // Nothing extra needed — CloseApplications=force handles process shutdown
  end;
end;
