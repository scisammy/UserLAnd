#! /bin/bash

if [ -d /sdcard ]; then
  if [ ! -d ~/sdcard ]; then
    ln -s /sdcard ~/sdcard
  fi
fi

SCRIPT_PATH=$(realpath ${BASH_SOURCE})
sudo rm -f $SCRIPT_PATH

libreoffice

exit
