!macro NSIS_HOOK_POSTINSTALL
  nsExec::ExecToStack '"$SYSDIR\schtasks.exe" /Query /TN "DailyCheckWidget"'
  Pop $0
  Pop $1
  ${If} $0 == 0
    nsExec::ExecToLog '"$SYSDIR\schtasks.exe" /Change /TN "DailyCheckWidget" /TR "$\"$INSTDIR\daily-check-widget.exe$\" --startup"'
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "DailyCheckWidget"
  ${Else}
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "DailyCheckWidget" '$\"$INSTDIR\daily-check-widget.exe$\" --startup'
  ${EndIf}
!macroend
!macro NSIS_HOOK_POSTUNINSTALL
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "DailyCheckWidget"
  nsExec::ExecToLog '"$SYSDIR\schtasks.exe" /Delete /TN "DailyCheckWidget" /F'
!macroend
