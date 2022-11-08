#!/system/bin/sh
# First: join all arguments with '+' as delimiter
#
# For example: if this was run as `media_provider method arg1 arg2`, the `cmd` variable below
# would be `method+arg1+arg2`

old_ifs=$IFS
IFS='+'
cmd=$(echo "$*")
IFS=$old_ifs

# Second: do `content read`.
#
# It may look like `content call` could be a better fit, but it's actually not the case:
# `content call` does not allow us to redirect the output of the command to the out FS (even
# though android.os.Bundle can hold (Parcelable) FDs com.android.commands.content.Content can't
# handle it).
# See: http://cs/android-internal/frameworks/base/cmds/content/src/com/android/commands/content/Content.java;l=303;rcl=b3370acc279c39e98823a2dbb9835fe0db615579
#
# `content read`, on the other hand, nicely copies content of the FD receives from the
# ContentProvider to the System.out.
# See: http://cs/android-internal/frameworks/base/cmds/content/src/com/android/commands/content/Content.java;l=630;rcl=b3370acc279c39e98823a2dbb9835fe0db615579

content read --uri content://media/cli?cmd="$cmd"
