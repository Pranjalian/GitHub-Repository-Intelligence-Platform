import { useId } from 'react'

/** Placeholder: the URL input only. Submission logic arrives in MVP 1 (Step 1.10). */
export default function AddRepositoryPage() {
  const inputId = useId()
  return (
    <div className="max-w-xl">
      <h2 className="text-xl font-semibold">Add Repository</h2>
      <p className="mt-1 text-sm text-slate-600">Enter the URL of a public GitHub repository.</p>
      <div className="mt-4">
        <label htmlFor={inputId} className="block text-sm font-medium">
          Repository URL
        </label>
        <input
          id={inputId}
          type="text"
          placeholder="https://github.com/owner/repo"
          className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm"
        />
      </div>
    </div>
  )
}
