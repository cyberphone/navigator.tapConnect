:: Copyright 2014 The Chromium Authors. All rights reserved.
:: Use of this source code is governed by a BSD-style license that can be
:: found in the LICENSE file.

:: Adapted for the navigator.tapConnect API by A.Rundgren

:: Change HKCU to HKLM if you want to install globally.
:: %~dp0 is the directory containing this bat script and ends with a backslash.
REG ADD "HKCU\Software\Google\Chrome\NativeMessagingHosts\org.webpki.tapconnect" /ve /t REG_SZ /d "%~dp0org.webpki.tapconnect.json" /f
COPY /Y "%~dp0..\windows-build\Debug\TapConnect.exe" "%~dp0"
COPY /Y "%~dp0..\windows-nfc\NFCWriter\bin\NFCWriter.exe" "%~dp0"
