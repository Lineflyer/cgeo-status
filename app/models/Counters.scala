package models

object Counters {

  private val counters: Map[BuildKind, Counter] =
    Map(Release -> new Counter(500),
      ReleaseCandidate -> new Counter(10),
      NightlyBuild -> new Counter(10))

  def reset(kind: BuildKind) {
    counters(kind).reset()
  }

  def count(kind: BuildKind) {
    counters(kind).count()
  }

  def users(kind: BuildKind) = counters(kind).users

}
