#ifndef SourceDir
  #define SourceDir "..\dist\AI Live Edge"
#endif
#ifndef OutputDir
  #define OutputDir "..\dist\installer"
#endif
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif

#define AppName "AI Live Edge"
#define AppExeName "AI Live Edge.exe"
#define WebView2ClientGuid "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"

[Setup]
AppId={{BE32CB43-71D0-4E83-9D16-C174F105E8B9}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=AI Live
DefaultDirName={localappdata}\Programs\AI Live Edge
DefaultGroupName=AI Live Edge
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=AI-Live-Edge-Setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#AppExeName}
CloseApplications=yes
RestartApplications=no
SetupLogging=yes

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autodesktop}\AI Live Edge"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"
Name: "{group}\AI Live Edge"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"
Name: "{group}\卸载 AI Live Edge"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\{#AppExeName}"; Description: "启动 AI Live Edge"; \
  Flags: nowait postinstall skipifsilent

[Code]
function IsNonZeroVersion(const VersionText: String): Boolean;
begin
  Result := (VersionText <> '') and (VersionText <> '0.0.0.0');
end;

function IsWebView2Installed(): Boolean;
var
  VersionText: String;
  ClientKey: String;
begin
  ClientKey := 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{#WebView2ClientGuid}';
  Result :=
    (RegQueryStringValue(HKCU, ClientKey, 'pv', VersionText) and IsNonZeroVersion(VersionText)) or
    (RegQueryStringValue(HKLM32, ClientKey, 'pv', VersionText) and IsNonZeroVersion(VersionText)) or
    (RegQueryStringValue(HKLM64, ClientKey, 'pv', VersionText) and IsNonZeroVersion(VersionText));
end;

function ShouldInstallWebView2(): Boolean;
begin
  Result := (not IsWebView2Installed()) and
            FileExists(ExpandConstant('{app}\resources\redist\webview2-installer.exe'));
end;


procedure CurStepChanged(CurStep: TSetupStep);
var
  ResultCode: Integer;
begin
  ResultCode := -1;
  if (CurStep = ssPostInstall) and ShouldInstallWebView2() then
  begin
    WizardForm.StatusLabel.Caption := '正在安装 Microsoft Edge WebView2 Runtime……';
    if (not Exec(ExpandConstant('{app}\resources\redist\webview2-installer.exe'),
                 '/silent /install', '', SW_HIDE, ewWaitUntilTerminated, ResultCode)) or
       (ResultCode <> 0) then
    begin
      MsgBox('Microsoft Edge WebView2 Runtime 安装失败。' + #13#10 +
             'AI Live Edge Desktop 将在启动时显示缺失提示。' + #13#10 +
             '安装程序退出代码：' + IntToStr(ResultCode),
             mbError, MB_OK);
    end;
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  UserDataDir: String;
begin
  if CurUninstallStep = usUninstall then
  begin
    UserDataDir := ExpandConstant('{localappdata}\AI Live Edge');
    if DirExists(UserDataDir) then
    begin
      if MsgBox('是否同时删除 AI Live Edge 用户数据？' + #13#10 + #13#10 +
                '选择“否”将保留素材、配置、Token 和日志。',
                mbConfirmation, MB_YESNO) = IDYES then
      begin
        DelTree(UserDataDir, True, True, True);
      end;
    end;
  end;
end;
