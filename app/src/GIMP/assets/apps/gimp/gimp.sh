#! /bin/bash

if [ -d /sdcard ]; then
  if [ ! -d ~/sdcard ]; then
    ln -s /sdcard ~/sdcard
  fi
fi

xterm -e "echo -e '\033[1;31mPLEASE WAIT WHILE GIMP LOADS\033[0m'; bash" &

SCRIPT_PATH=$(realpath ${BASH_SOURCE})
sudo rm -f $SCRIPT_PATH

echo -e "\033[1;31mPLEASE WAIT WHILE GIMP LOADS\033[0m"

if command -v wmctrl &> /dev/null; then
(
until wmctrl -l | grep -i "GNU Image Manipulation Program"; do
    sleep 0.5
done
wmctrl -r "GNU Image Manipulation Program" -b add,fullscreen
) &
fi

gimp

exit
