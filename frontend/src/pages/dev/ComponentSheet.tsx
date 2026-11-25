import { useState } from 'react'
import {
  Accordion,
  Badge,
  BarList,
  Button,
  CapabilityBadge,
  Card,
  CodeBlock,
  ConfidenceBadge,
  CopyButton,
  DataTable,
  Donut,
  EmptyState,
  ErrorState,
  FilterChip,
  HttpMethodBadge,
  Icon,
  IconButton,
  Input,
  KindBadge,
  Notice,
  ProgressBar,
  ProviderBadge,
  RunStatusLabel,
  SegmentedBar,
  SegmentedControl,
  SeverityBadge,
  Skeleton,
  StatCard,
  StatusLabel,
  Stepper,
  Tabs,
  UnavailableState,
  VulnerabilityStatusBadge,
} from '../../components/ui'
import { ICON_NAMES } from '../../components/ui/icons'
import { useTheme } from '../../lib/theme'
import type { RunStatus } from '../../types/api'
import type { ExecutionStatus } from '../../types/status'

/** Developer-only (import.meta.env.DEV): every primitive in every state, to compare with the Stitch component sheet in both themes. */
export default function ComponentSheet() {
  const { choice, setChoice } = useTheme()
  const [seg, setSeg] = useState('a')
  const [tab, setTab] = useState('one')
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-headline-lg font-semibold">Component sheet</h1>
        <SegmentedControl label="Theme" value={choice} onChange={setChoice} options={[{ value: 'light', label: 'Light' }, { value: 'dark', label: 'Dark' }, { value: 'system', label: 'System' }]} />
      </div>
      <Card title="Severity and confidence">
        <div className="flex flex-wrap gap-2">
          {(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const).map((s) => <SeverityBadge key={s} severity={s} />)}
          {(['DETECTED', 'LIKELY', 'POSSIBLE', 'NOT_VERIFIED'] as const).map((c) => <ConfidenceBadge key={c} confidence={c} />)}
          {(['DEEP', 'BASIC', 'GENERIC', 'UNSUPPORTED', 'UNAVAILABLE'] as const).map((c) => <CapabilityBadge key={c} level={c} />)}
          {(['NOT_CHECKED', 'NOT_CHECKABLE', 'NO_KNOWN_VULNERABILITIES', 'VULNERABLE'] as const).map((v) => <VulnerabilityStatusBadge key={v} status={v} />)}
          {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => <HttpMethodBadge key={m} method={m} />)}
          <ProviderBadge kind="local" /><ProviderBadge kind="hosted" /><ProviderBadge kind="unavailable" />
          <KindBadge kind="METHOD" /><KindBadge kind="CLASS" /><KindBadge kind="INTERFACE" /><KindBadge kind="CONFIG" />
        </div>
      </Card>
      <Card title="Run and analyzer status">
        <div className="flex flex-wrap gap-2">
          {(['QUEUED', 'INGESTING', 'ANALYZING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED', 'CANCELLED'] as RunStatus[]).map((s) => <RunStatusLabel key={s} status={s} />)}
          {(['COMPLETED', 'COMPLETED_WITH_WARNINGS', 'NOT_CONFIGURED', 'NOT_SUPPORTED', 'UNAVAILABLE', 'SKIPPED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'NOT_ANALYZED'] as ExecutionStatus[]).map((s) => <StatusLabel key={s} status={s} />)}
          <Badge tone="ai" icon="sparkle">AI-generated</Badge>
        </div>
      </Card>
      <Card title="Controls">
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="primary">Primary</Button><Button>Secondary</Button><Button variant="ghost">Ghost</Button>
          <Button variant="danger">Danger style</Button><Button variant="ai" icon="sparkle">AI action</Button>
          <Button variant="primary" busy>Analyzing...</Button><Button disabled>Disabled</Button>
          <IconButton icon="refresh" label="Refresh" /><CopyButton value="500158f" />
          <SegmentedControl label="Mode" value={seg} onChange={setSeg} options={[{ value: 'a', label: 'Symbol' }, { value: 'b', label: 'Text' }]} />
          <FilterChip selected onClick={() => {}} count={3}>High</FilterChip><FilterChip selected={false} onClick={() => {}}>Low</FilterChip>
          <Input icon="search" hint="/" aria-label="Filter" placeholder="Filter" />
        </div>
        <div className="mt-3"><Tabs label="Demo" active={tab} onChange={setTab} tabs={[{ id: 'one', label: 'One', count: 3 }, { id: 'two', label: 'Two' }]} /></div>
        <div className="mt-3"><Accordion title="Evidence">pom.xml:21 matched rule</Accordion></div>
      </Card>
      <div className="grid gap-4 md:grid-cols-3">
        <StatCard label="Total analyzed" value="4" sub="repositories" />
        <StatCard label="Coverage" value="Unavailable" sub="No committed JaCoCo report" />
        <Card title="Charts"><Donut slices={[{ label: 'Java', value: 28 }, { label: 'XML', value: 2 }]} unit="files" total={30} /><SegmentedBar label="Share" segments={[{ label: 'a', value: 3 }, { label: 'b', value: 1 }]} /><ProgressBar value={40} label="Progress" /><BarList rows={[{ label: '.java', value: 28 }, { label: '.xml', value: 2 }]} unit="files" /></Card>
      </div>
      <Card title="Table, code, steps">
        <DataTable caption="Demo" rowKey={(r) => r.name} rows={[{ name: 'spring-core', v: '6.1.0' }]} columns={[{ key: 'n', header: 'Name', cell: (r) => <code>{r.name}</code> }, { key: 'v', header: 'Version', cell: (r) => r.v }]} />
        <div className="mt-3"><CodeBlock code={'line one\nline two\n+ added\n- removed'} highlight={[2]} diff label="Example.java" /></div>
        <div className="mt-3"><Stepper label="Run" steps={[{ id: 'a', label: 'Queued', state: 'done' }, { id: 'b', label: 'Analyzing', state: 'active' }, { id: 'c', label: 'Finished', state: 'pending' }]} /></div>
      </Card>
      <div className="grid gap-4 md:grid-cols-2">
        <UnavailableState title="Not analyzed" reason="No analyzer exists for this technology." />
        <ErrorState message="Something failed" />
        <Notice>An informational notice.</Notice><Notice tone="warning">A warning notice.</Notice>
        <EmptyState title="Empty" description="Nothing here yet." />
        <div className="space-y-2"><Skeleton className="h-4 w-1/2" /><Skeleton className="h-10 w-full" /></div>
      </div>
      <Card title="Icons">
        <ul className="grid grid-cols-6 gap-2 text-body-sm text-fg-2 md:grid-cols-10">
          {ICON_NAMES.map((n) => <li key={n} className="flex flex-col items-center gap-1"><Icon name={n} size={20} />{n}</li>)}
        </ul>
      </Card>
    </div>
  )
}
