// SPDX-License-Identifier: MIT
// Copyright (c) 2026 ryuya0124
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/mount.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#define SOURCE "/data/media/150"
#define TARGET_MAIN "/data/media/0/SecureFolder"
#define TARGET_WORK "/data/media/10/SecureFolder"
#define PRIVATE "/data/local/tmp/dev.ryuya.securefolderbridge"
static const char *target;
static char control[1024];
static char marker_path[1100];

static int error(const char *operation) {
  fprintf(stderr, "%s: %s\n", operation, strerror(errno));
  return 1;
}

static int root_directory(const char *path) {
  if (mkdir(path, 0700) && errno != EEXIST)
    return error("private directory");
  struct stat st;
  if (lstat(path, &st) || !S_ISDIR(st.st_mode) || st.st_uid != 0 || (st.st_mode & 077)) {
    errno = EPERM;
    return error("private directory owner");
  }
  return 0;
}

static int owned_target(void) {
  struct stat st;
  return !lstat(marker_path, &st) && S_ISREG(st.st_mode) && st.st_uid == 0 && !(st.st_mode & 077);
}

static int mounted(void) {
  FILE *stream = fopen("/proc/self/mountinfo", "re");
  if (!stream)
    return -1;
  char line[8192], root[1024], mount_target[1024];
  int found = 0;
  while (fgets(line, sizeof(line), stream)) {
    if (sscanf(line, "%*u %*u %*s %1023s %1023s", root, mount_target) == 2 && !strcmp(mount_target, target)) {
      // Bind root must be the exact Secure Folder shared-storage root.
      found = !strcmp(root, "/media/150") ? 1 : -1;
      break;
    }
  }
  fclose(stream);
  return found;
}

int main(int argc, char **argv) {
  if (getuid() != 0 || (argc != 2 && argc != 3)) {
    fprintf(stderr, "Root and one operation are required\n");
    return 2;
  }
  const char *user = argc == 3 ? argv[2] : "0";
  if (strcmp(user, "0") && strcmp(user, "10"))
    return 2;
  target = !strcmp(user, "10") ? TARGET_WORK : TARGET_MAIN;
  snprintf(control, sizeof(control), "%s/user%s", PRIVATE, user);
  snprintf(marker_path, sizeof(marker_path), "%s/target-owner", control);
  if (root_directory(PRIVATE) || root_directory(control))
    return 1;
  signal(SIGPIPE, SIG_IGN);
  int keep = !strncmp(argv[1], "keep-", 5);
  const char *operation = keep ? argv[1] + 5 : argv[1];
  if (!strcmp(argv[1], "status")) {
    puts(mounted() == 1 && owned_target() ? "CONNECTED" : "OFF");
    return 0;
  }
  if (!strcmp(argv[1], "unmount")) {
    int active = mounted();
    if (!active && !owned_target())
      return 0;
    if (!owned_target() || active < 0) {
      errno = EPERM;
      return error("unowned mount");
    }
    if (active && umount2(target, MNT_DETACH))
      return error("unmount");
    if (rmdir(target) && errno != ENOENT && errno != ENOTEMPTY)
      return error("remove empty mount point");
    return 0;
  }
  int readonly = !strcmp(operation, "mount-ro");
  if (!readonly && strcmp(operation, "mount-rw"))
    return 2;
  struct stat source;
  if (lstat(SOURCE, &source) || !S_ISDIR(source.st_mode))
    return error("Secure Folder shared storage");
  if (mounted() != 0) {
    errno = EBUSY;
    return error("existing mount");
  }
  struct stat target_stat;
  if (!lstat(target, &target_stat)) {
    if (!owned_target() || !S_ISDIR(target_stat.st_mode)) {
      errno = EEXIST;
      return error("existing folder is not owned by this app");
    }
    if (rmdir(target))
      return error("mount point is not empty");
  } else if (errno != ENOENT) {
    return error("mount point");
  }
  if (mkdir(target, 0770) || chown(target, 1023, 1023) || chmod(target, 02770))
    return error("create mount point");
  int marker = open(marker_path, O_WRONLY | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
  if (marker < 0)
    return error("ownership marker");
  close(marker);
  if (readonly) {
    // A later MS_REMOUNT does not update copies already propagated into the
    // MediaProvider namespace. Make a detached clone read-only before publishing it.
    int tree = (int)syscall(SYS_open_tree, AT_FDCWD, SOURCE, OPEN_TREE_CLONE | OPEN_TREE_CLOEXEC);
    if (tree < 0)
      return error("clone detached mount");
    struct mount_attr attributes = {.attr_set = MOUNT_ATTR_RDONLY};
    int changed = (int)syscall(SYS_mount_setattr, tree, "", AT_EMPTY_PATH, &attributes,
                              sizeof(attributes));
    if (!changed)
      changed = (int)syscall(SYS_move_mount, tree, "", AT_FDCWD, target, MOVE_MOUNT_F_EMPTY_PATH);
    int saved = errno;
    close(tree);
    if (changed) {
      rmdir(target);
      errno = saved;
      return error("publish read-only mount");
    }
  } else if (mount(SOURCE, target, NULL, MS_BIND, NULL)) {
    return error("bind mount");
  }
  puts(readonly ? "READ_ONLY" : "READ_WRITE");
  fflush(stdout);
  if (keep) {
    // The root controller owns the only write end. Its exit, including a crash
    // or APK replacement, closes the pipe and detaches the mount here.
    char stop;
    ssize_t received;
    do {
      received = read(STDIN_FILENO, &stop, 1);
    } while (received < 0 && errno == EINTR);
    if (umount2(target, MNT_DETACH))
      return error("detach after controller exit");
    rmdir(target);
    puts("OFF");
  }
  return 0;
}
