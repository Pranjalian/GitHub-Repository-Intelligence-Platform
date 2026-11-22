import { isCompleted, type Snapshot } from '../types/api'

/** The snapshot to show: the one in the URL, else the latest completed, else the newest. */
export function chooseSnapshot(snapshots: Snapshot[], requestedId: string | null): Snapshot | undefined {
  return (
    snapshots.find((snapshot) => snapshot.id === requestedId) ??
    snapshots.find((snapshot) => isCompleted(snapshot.latestRun?.status)) ??
    snapshots[0]
  )
}
