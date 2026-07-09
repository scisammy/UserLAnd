#include <stdbool.h>

/*
 * Stubs for libvhost-user symbols referenced by util/vhost-user-server.c,
 * which lands in libqemu-common.a. The real library wasn't built because
 * --disable-vhost-user was passed at configure time; these stubs satisfy
 * the linker without pulling in the full vhost-user implementation.
 */

bool vu_init(void *dev, unsigned short max_queues, int socket,
             void *panic, void *read_msg,
             void *set_watch, void *remove_watch, const void *iface)
{
    (void)dev; (void)max_queues; (void)socket; (void)panic;
    (void)read_msg; (void)set_watch; (void)remove_watch; (void)iface;
    return false;
}

void vu_deinit(void *dev) { (void)dev; }

bool vu_dispatch(void *dev) { (void)dev; return false; }
