#! /bin/bash

if [ -d /sdcard ]; then
  if [ ! -d ~/sdcard ]; then
    ln -s /sdcard ~/sdcard
  fi
fi

xterm &

SCRIPT_PATH=$(realpath ${BASH_SOURCE})
sudo rm -f $SCRIPT_PATH

if [ ! -f /support/gdk_fix ]; then
  update-mime-database /usr/share/mime
  find /usr/lib -name gdk-pixbuf-query-loaders -exec {} --update-cache \;
  touch /support/gdk_fix
fi

/usr/bin/code --no-sandbox

exit
