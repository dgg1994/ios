@echo off
setlocal
cd /d D:\workTwo\ios\26-ios-pom\26-consumer-server
set CP=target\classes
set /p DEP=<26-consumer-server\target\cp.txt
java -cp "%CP%;%DEP%" com.consumer.parse.LocalDeviceScanMain D:/v26_records/uploads 2026-09-16 2c18a61b-8d0f-4371-bbd1-b26deb19c4a3
