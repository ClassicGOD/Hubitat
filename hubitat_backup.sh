#!/bin/sh
#script to automatically download latest backup from password protected hub

ip_address=hub.ip.addr
username="HUB_LOGIN"
password="HUB_PASS"
cookie_file=/path/where/cookie/file/will/be/temporarly/created/hubitat.cookie
backup_path=/path/where/backups/will/be/stored/

curl -k -c $cookie_file -d username=$username -d password=$password http://$ip_address/login
wget --load-cookies=$cookie_file --content-disposition -P $backup_path http://$ip_address/hub/backupDB?fileName=latest
rm $cookie_file
