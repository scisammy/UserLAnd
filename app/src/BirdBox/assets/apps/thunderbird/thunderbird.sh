#! /bin/bash

if [ -d /sdcard ]; then
  if [ ! -d ~/sdcard ]; then
    ln -s /sdcard ~/sdcard
  fi
fi

xterm &

SCRIPT_PATH=$(realpath ${BASH_SOURCE})
sudo rm -f $SCRIPT_PATH

echo -e "\033[1;31mPLEASE WAIT WHILE THUNDERBIRD LOADS\033[0m"

if command -v wmctrl &> /dev/null; then
(
until wmctrl -l | grep 'Thunderbird$'; do
    sleep 0.5
done
wmctrl -r "Mozilla Thunderbird" -b add,fullscreen
wmctrl -r "Account Setup - Mozilla Thunderbird" -b add,fullscreen
wmctrl -r "Home - Mozilla Thunderbird" -b add,fullscreen
) &
fi

MOZ_DISABLE_CONTENT_SANDBOX=1 /usr/bin/thunderbird

exit
