#! /bin/bash

if [ -d /sdcard ]; then
  if [ ! -d ~/sdcard ]; then
    ln -s /sdcard ~/sdcard
  fi
fi

SCRIPT_PATH=$(realpath ${BASH_SOURCE})
sudo rm -f $SCRIPT_PATH

if [[ -z "${DISPLAY}" ]]; then
   GNUTERM=dumb /usr/bin/gnuplot
else
   GNUTERM=x11 /usr/bin/gnuplot
fi
exit
