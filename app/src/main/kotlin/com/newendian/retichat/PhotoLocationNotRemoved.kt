package com.newendian.retichat

/**
 * A photo [filename] would leave with where it was taken, and could not be made
 * safe ([PhotoMetadata.withoutLocation] failed on it): nothing of the message is
 * stored or sent. The message is the user's, as the web's.
 */
class PhotoLocationNotRemoved(val filename: String) : Exception(messageFor(filename)) {
    companion object {
        fun messageFor(filename: String) = "$filename: where it was taken could not be removed from it, so it is not sent."
    }
}
