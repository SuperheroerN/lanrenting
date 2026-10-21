package voice.core.online

import kotlinx.serialization.Serializable

/**
 * The verdict of the last key info query for the built-in lrts source.
 * `key` records which key the verdict belongs to, so a stale verdict for a
 * since-changed key is never shown as if it still held.
 */
@Serializable
public data class LrtsKeyStatus(
  val key: String = "",
  val state: String = STATE_NONE,
  val expiresAt: String = "",
  val message: String = "",
) {
  public companion object {
    public const val STATE_NONE: String = "none"
    public const val STATE_OK: String = "ok"
    public const val STATE_INVALID: String = "invalid"
    public const val STATE_ERROR: String = "error"
  }
}
