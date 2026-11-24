package chat.secretchat.admin;

/** Fixed code locations can identify a failed check without serializing exception messages or arguments. */
final class FailureSite {
    static String of(Throwable failure) {
        String site = "framework";
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            for (StackTraceElement frame : failure.getStackTrace()) {
                String type = frame.getClassName();
                if (type.startsWith("chat.secretchat.admin.") && !type.contains("$$")
                        && !type.equals(RequestDiagnostics.class.getName())) {
                    site = type.substring(type.lastIndexOf('.') + 1) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                    break;
                }
            }
        }
        return site;
    }

    private FailureSite() {}
}
