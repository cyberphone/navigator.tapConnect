:: Copyright 2014 The Chromium Authors. All rights reserved.
:: Use of this source code is governed by a BSD-style license that can be
:: found in the LICENSE file.

:: Adapted for the navigator.tapConnect API by A.Rundgren

:: Deletes the entries created by install_host.bat
REG DELETE "HKCU\Software\Google\Chrome\NativeMessagingHosts\org.webpki.tapconnect" /f
DEL /Q "%~dp0TapConnect.exe"
