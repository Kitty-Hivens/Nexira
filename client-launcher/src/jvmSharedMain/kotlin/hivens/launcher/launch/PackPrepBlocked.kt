package hivens.launcher.launch

import hivens.core.launch.LaunchError

/**
 * Thrown from the pack launch service when an SC-bound preparation step cannot
 * complete (patched authlib or open-smrt helper unavailable). Carries the
 * semantic [error] so the controller maps it to the right [LaunchError] surface
 * instead of the generic [LaunchError.Internal].
 */
class PackPrepBlocked(val error: LaunchError) : Exception()
