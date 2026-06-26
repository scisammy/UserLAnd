#! /bin/bash

SCRIPT_PATH=$(realpath ${BASH_SOURCE})
sudo rm -f $SCRIPT_PATH

if [ -d /sdcard ]; then
  if [ ! -d ~/sdcard ]; then
    ln -s /sdcard ~/sdcard
  fi
fi

xterm &

echo -e "\033[1;31mPLEASE WAIT WHILE AUDACITY LOADS\033[0m"

if command -v wmctrl &> /dev/null; then
(
until wmctrl -l | grep 'Audacity$'; do
    sleep 0.5
done
wmctrl -r "Audacity" -b add,fullscreen
) &
fi

audacity

exit
