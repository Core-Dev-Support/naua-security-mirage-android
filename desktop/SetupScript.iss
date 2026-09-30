; Script generated for NAUA Security Mirage PC Installer
; Strictly contains ONLY two shortcut options: Desktop and Start Menu!

#define MyAppName "NAUA Security Mirage"
#define MyAppVersion "1.3.0"
#define MyAppPublisher "Core Dev Support"
#define MyAppURL "https://github.com/coredevsupport-hub/naua-security-mirage-android"
#define MyAppExeName "NAUASecurityMirage.exe"

[Setup]
AppId={{D1A3F5B8-438B-4FA0-A3BB-C447BF246A10}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={autopf}\{#MyAppName}
DisableProgramGroupPage=yes
DefaultGroupName={#MyAppName}
OutputDir=..\build\installer
OutputBaseFilename=NAUA-Security-Mirage-Setup-v{#MyAppVersion}
SetupIconFile=app.ico
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Добавить ярлык на рабочий стол"; GroupDescription: "Ярлыки:"; Flags: unchecked
Name: "startmenuicon"; Description: "Добавить в меню Пуск"; GroupDescription: "Ярлыки:"; Flags: checkedonce

[Files]
Source: "build\compose\binaries\main\app\NAUASecurityMirage\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "Core\*"; DestDir: "{app}\Core"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "app.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\app.ico"; Tasks: startmenuicon
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\app.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent
