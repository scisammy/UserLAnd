#! /bin/bash

if [ -d /sdcard ]; then
  if [ ! -d ~/sdcard ]; then
    ln -s /sdcard ~/sdcard
  fi
fi

xterm &

SCRIPT_PATH=$(realpath ${BASH_SOURCE})
sudo rm -f $SCRIPT_PATH

(
until wmctrl -l | grep -i "Inkscape"; do
    sleep 0.5
done
wmctrl -r "Inkscape" -b add,fullscreen
) &

inkscape

exit
