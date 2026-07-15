import { useState, useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, RefreshCw, Trash2, ScanSearch, Settings, Zap, PlayCircle } from 'lucide-react'
import {
  getRagTables,
  createRagTable,
  deleteRagTable,
  resyncRagTable,
  updateRagTableStatus,
  bulkImportRagTables,
  bulkDeleteRagTables,
  bulkResyncRagTables,
  getRagSyncStatus,
  type RagTable,
} from '../../api/admin/ragTables'
import {
  getSyncMode,
  updateSyncMode,
  replaySyncMode,
  getRagDriftStatus,
  type DriftEntry,
} from '../../api/admin/syncMode'
import { triggerSync } from '../../api/admin/sync'
import SchemaDiscoverModal from '../../components/admin/SchemaDiscoverModal'
import RagColumnConfigModal from '../../components/admin/RagColumnConfigModal'
import Tooltip from '../../components/common/Tooltip'

export default function RagTablesPage() {
  const { dsId: dsIdStr } = useParams<{ dsId: string }>()
  const dsId = Number(dsIdStr)
  const qc = useQueryClient()

  const { data: tables = [], isLoading, error } = useQuery({
    queryKey: ['admin-rag-tables', dsId],
    queryFn: () => getRagTables(dsId),
    enabled: !!dsId,
    refetchInterval: (query) => {
      const data = query.state.data as RagTable[] | undefined
      return data?.some(t => t.llmStatus === 'pending') ? 3000 : false
    },
  })

  const { data: syncMode, refetch: refetchSyncMode } = useQuery({
    queryKey: ['sync-mode', dsId],
    queryFn: () => getSyncMode(dsId),
    enabled: !!dsId,
  })

  const ragAutoSync = syncMode?.rag.autoSyncEnabled ?? false
  const ragDisabledAt = syncMode?.rag.disabledAt ?? null

  const { data: driftData = [], refetch: refetchDrift, isFetching: driftFetching } = useQuery({
    queryKey: ['rag-drift', dsId],
    queryFn: () => getRagDriftStatus(dsId),
    enabled: false,
  })

  const driftMap = new Map<string, DriftEntry>(driftData.map(d => [d.tableName, d]))

  const toggleSyncMut = useMutation({
    mutationFn: (enabled: boolean) => updateSyncMode(dsId, 'rag', enabled),
    onSuccess: () => refetchSyncMode(),
  })


  const handleToggleAutoSync = async () => {
    const next = !ragAutoSync
    if (next && ragDisabledAt) {
      const ok = window.confirm(
        `비활성화 시점(${new Date(ragDisabledAt).toLocaleString()})부터 현재까지의 변경사항을 즉시 자동 동기화하시겠습니까?`
      )
      if (!ok) return
      await toggleSyncMut.mutateAsync(true)
      setIsRagSyncing(true)
    } else {
      await toggleSyncMut.mutateAsync(next)
    }
  }

  const createMut = useMutation({
    mutationFn: (body: Partial<RagTable>) => createRagTable(dsId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-rag-tables', dsId] })
      setShowForm(false)
      setForm({ sourceTable: '' })
    },
  })

  const deleteMut = useMutation({
    mutationFn: (sourceTable: string) => deleteRagTable(dsId, sourceTable),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-rag-tables', dsId] }),
  })

  const resyncMut = useMutation({
    mutationFn: (sourceTable: string) => resyncRagTable(dsId, sourceTable),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-rag-tables', dsId] }),
  })

  const statusMut = useMutation({
    mutationFn: ({ sourceTable, isActive }: { sourceTable: string; isActive: boolean }) =>
      updateRagTableStatus(dsId, sourceTable, isActive),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-rag-tables', dsId] }),
  })

  const syncNowMut = useMutation({
    mutationFn: async () => {
      const job = await triggerSync(dsId)
      const replay = await replaySyncMode(dsId, 'rag')
      return { job, replay }
    },
    onSuccess: ({ job, replay }) => {
      qc.invalidateQueries({ queryKey: ['admin-rag-tables', dsId] })
      if (job.status === 'failed') {
        setSyncError(job.errorMessage ?? '동기화 실패')
      } else {
        setBulkResult(
          `동기화 완료: 성공 ${job.recordsSuccess}건 / 실패 ${job.recordsFailed}건, ` +
          `화이트리스트 반영: ${replay.applied}개 적용 / ${replay.skipped}개 건너뜀`
        )
      }
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : String(err)
      setSyncError(`동기화 요청 실패: ${msg}`)
    },
  })

  const handleSyncNow = () => {
    if (!ragAutoSync) {
      const ok = window.confirm(
        '밀린 스키마 변경사항(컬럼 추가/삭제/이름변경)을 RAG 설정에 반영합니다. ' +
        '새로 추가된 컬럼은 검토 없이 즉시 임베딩·검색 대상에 포함될 수 있습니다. 계속할까요?'
      )
      if (!ok) return
    }
    syncNowMut.mutate()
  }

  const [isRagSyncing, setIsRagSyncing] = useState(false)

  useEffect(() => {
    if (!isRagSyncing) return
    const interval = setInterval(async () => {
      try {
        const status = await getRagSyncStatus(dsId)
        if (!status.syncing) {
          setIsRagSyncing(false)
          qc.invalidateQueries({ queryKey: ['admin-rag-tables', dsId] })
          clearInterval(interval)
        }
      } catch {
        setIsRagSyncing(false)
        clearInterval(interval)
      }
    }, 2000)
    return () => clearInterval(interval)
  }, [isRagSyncing, dsId])

  // ── state ─────────────────────────────────────────────────────────────────
  const [showForm, setShowForm] = useState(false)
  const [showDiscover, setShowDiscover] = useState(false)
  const [configTable, setConfigTable] = useState<RagTable | null>(null)
  const [form, setForm] = useState({ sourceTable: '' })
  const [syncing, setSyncing] = useState<string | null>(null)
  const [importResult, setImportResult] = useState<{ imported: string[]; skipped: string[] } | null>(null)
  const [bulkResult, setBulkResult] = useState<string | null>(null)
  const [syncError, setSyncError] = useState<string | null>(null)

  // ── checkbox selection ────────────────────────────────────────────────────
  const [selected, setSelected] = useState<Set<string>>(new Set())

  useEffect(() => {
    setSelected(new Set())
  }, [dsId])
  const allSelected = tables.length > 0 && tables.every(t => selected.has(t.sourceTable))
  const someSelected = selected.size > 0

  const toggleRow = (sourceTable: string) =>
    setSelected(prev => {
      const next = new Set(prev)
      next.has(sourceTable) ? next.delete(sourceTable) : next.add(sourceTable)
      return next
    })

  const toggleAll = () =>
    setSelected(allSelected ? new Set() : new Set(tables.map(t => t.sourceTable)))

  // ── bulk actions ──────────────────────────────────────────────────────────
  const [bulkLoading, setBulkLoading] = useState<'delete' | 'resync' | null>(null)

  const handleBulkDelete = async () => {
    if (!window.confirm(`선택한 ${selected.size}개 테이블을 삭제하시겠습니까?`)) return
    setBulkLoading('delete')
    try {
      const result = await bulkDeleteRagTables(dsId, Array.from(selected))
      setSelected(new Set())
      qc.invalidateQueries({ queryKey: ['admin-rag-tables', dsId] })
      setBulkResult(`${result.succeeded.length}개 삭제 완료${result.failed.length > 0 ? ` (실패: ${result.failed.join(', ')})` : ''}`)
    } finally {
      setBulkLoading(null)
    }
  }

  const handleBulkResync = async () => {
    setBulkLoading('resync')
    try {
      const result = await bulkResyncRagTables(dsId, Array.from(selected))
      qc.invalidateQueries({ queryKey: ['admin-rag-tables', dsId] })
      setBulkResult(`${result.succeeded.length}개 자동설정 시작${result.failed.length > 0 ? ` (실패: ${result.failed.join(', ')})` : ''}`)
    } finally {
      setBulkLoading(null)
    }
  }

  // ── single actions ────────────────────────────────────────────────────────
  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault()
    createMut.mutate({ sourceTable: form.sourceTable, sourceType: 'mysql', dataSensitivity: 'internal' })
  }

  const handleResync = async (sourceTable: string) => {
    setSyncing(sourceTable)
    try { await resyncMut.mutateAsync(sourceTable) } finally { setSyncing(null) }
  }

  const handleBulkImport = async (tableNames: string[]) => {
    const result = await bulkImportRagTables(dsId, tableNames)
    setImportResult(result)
    qc.invalidateQueries({ queryKey: ['admin-rag-tables', dsId] })
  }

  const contentCols = (t: RagTable) =>
    t.contentColumnsJson ? t.contentColumnsJson.split(',').filter(Boolean) : []

  return (
    <div className="p-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">RAG 테이블</h1>
          <p className="text-sm text-gray-500 mt-0.5">벡터 임베딩 대상 테이블을 관리합니다.</p>
        </div>
        <div className="flex gap-2 items-center">
          <Tooltip side="bottom" text={ragAutoSync ? '자동 동기화 ON — 클릭하면 비활성화' : '자동 동기화 OFF — 클릭하면 활성화'}>
            <button
              onClick={handleToggleAutoSync}
              disabled={toggleSyncMut.isPending || isRagSyncing}
              className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium border transition-colors disabled:opacity-50 ${
                isRagSyncing
                  ? 'bg-blue-50 border-blue-300 text-blue-700'
                  : ragAutoSync
                  ? 'bg-green-50 border-green-300 text-green-700 hover:bg-green-100'
                  : 'bg-gray-50 border-gray-300 text-gray-600 hover:bg-gray-100'
              }`}
            >
              <Zap size={14} className={isRagSyncing ? 'text-blue-600 animate-pulse' : ragAutoSync ? 'text-green-600' : 'text-gray-400'} />
              {isRagSyncing
                ? '자동 동기화중...'
                : ragAutoSync
                ? '자동 동기화 ON'
                : '자동 동기화 OFF'
              }
            </button>
          </Tooltip>
          {!ragAutoSync && (
            <Tooltip side="bottom" text="라이브 DB 스키마와 RAG 설정을 비교해 테이블 삭제·컬럼 변경 여부를 확인합니다 (읽기 전용, 변경 없음)">
              <button
                onClick={() => refetchDrift()}
                disabled={driftFetching}
                className="flex items-center gap-2 border border-orange-300 hover:bg-orange-50 text-orange-600 px-3 py-2 rounded-lg text-sm font-medium disabled:opacity-50"
              >
                <RefreshCw size={14} className={driftFetching ? 'animate-spin' : ''} />
                변경사항 확인
              </button>
            </Tooltip>
          )}
          <Tooltip side="bottom" text="MySQL binlog를 즉시 동기화하고 밀린 DDL을 RAG 설정에 반영합니다">
            <button
              onClick={handleSyncNow}
              disabled={syncNowMut.isPending}
              className="flex items-center gap-2 border border-blue-300 hover:bg-blue-50 text-blue-700 px-3 py-2 rounded-lg text-sm font-medium disabled:opacity-50"
            >
              <PlayCircle size={14} className={syncNowMut.isPending ? 'animate-spin' : ''} />
              {syncNowMut.isPending ? '동기화 중...' : '지금 동기화'}
            </button>
          </Tooltip>
          <Tooltip side="bottom" text="원본 DB의 전체 테이블을 조회해 RAG 대상에 없는 테이블을 일괄 등록합니다">
            <button
              onClick={() => setShowDiscover(true)}
              className="flex items-center gap-2 border border-gray-300 hover:bg-gray-50 text-gray-700 px-3 py-2 rounded-lg text-sm font-medium"
            >
              <ScanSearch size={15} /> 스키마 탐색
            </button>
          </Tooltip>
          <Tooltip side="bottom" align="end" text="테이블명을 직접 입력해 RAG 임베딩 대상으로 등록합니다">
            <button
              onClick={() => setShowForm(v => !v)}
              className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium"
            >
              <Plus size={15} /> 직접 등록
            </button>
          </Tooltip>
        </div>
      </div>

      {/* Notices */}
      {importResult && (
        <div className="mb-4 bg-blue-50 border border-blue-200 rounded-xl px-4 py-3 text-sm flex items-start justify-between gap-4">
          <div>
            <span className="font-medium text-blue-800">{importResult.imported.length}개 등록 완료</span>
            {importResult.skipped.length > 0 && (
              <span className="ml-2 text-blue-600">(건너뜀: {importResult.skipped.join(', ')})</span>
            )}
            <span className="ml-2 text-blue-500 text-xs">컬럼 설정 버튼으로 임베딩 컬럼을 확인·수정하세요.</span>
          </div>
          <button onClick={() => setImportResult(null)} className="text-blue-400 hover:text-blue-600 text-xs shrink-0">닫기</button>
        </div>
      )}
      {bulkResult && (
        <div className="mb-4 bg-gray-50 border border-gray-200 rounded-xl px-4 py-3 text-sm flex items-center justify-between">
          <span className="text-gray-700">{bulkResult}</span>
          <button onClick={() => setBulkResult(null)} className="text-gray-400 hover:text-gray-600 text-xs">닫기</button>
        </div>
      )}
      {syncError && (
        <div className="mb-4 bg-red-50 border border-red-200 rounded-xl px-4 py-3 text-sm flex items-start justify-between gap-4">
          <div>
            <span className="font-medium text-red-700">동기화 실패</span>
            <span className="ml-2 text-red-600">{syncError}</span>
          </div>
          <button onClick={() => setSyncError(null)} className="text-red-400 hover:text-red-600 text-xs shrink-0">닫기</button>
        </div>
      )}

      {/* Manual form */}
      {showForm && (
        <form
          onSubmit={handleCreate}
          className="mb-6 bg-white border border-gray-200 rounded-xl p-4 flex flex-wrap gap-3 items-end"
        >
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">테이블명</label>
            <input
              required
              value={form.sourceTable}
              onChange={e => setForm(p => ({ ...p, sourceTable: e.target.value }))}
              placeholder="table_name"
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-52 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <button type="submit" disabled={createMut.isPending}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium">
            {createMut.isPending ? '등록 중…' : '등록'}
          </button>
          <button type="button" onClick={() => setShowForm(false)} className="text-gray-500 px-3 py-1.5 text-sm">취소</button>
        </form>
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {/* Bulk action bar */}
      {someSelected && (
        <div className="mb-3 flex items-center gap-3 bg-blue-50 border border-blue-200 rounded-xl px-4 py-2.5">
          <span className="text-sm font-medium text-blue-800">{selected.size}개 선택됨</span>
          <div className="flex gap-2 ml-auto">
            <Tooltip text="선택한 테이블들의 컬럼 설정을 LLM으로 재분석하고 전체 데이터를 다시 임베딩합니다">
              <button
                onClick={handleBulkResync}
                disabled={bulkLoading !== null}
                className="flex items-center gap-1.5 border border-blue-300 text-blue-700 hover:bg-blue-100 px-3 py-1.5 rounded-lg text-xs font-medium disabled:opacity-50"
              >
                <RefreshCw size={12} className={bulkLoading === 'resync' ? 'animate-spin' : ''} />
                {bulkLoading === 'resync' ? '자동설정 중…' : '일괄 자동설정'}
              </button>
            </Tooltip>
            <Tooltip text="선택한 테이블을 한 번에 RAG 대상에서 삭제합니다">
              <button
                onClick={handleBulkDelete}
                disabled={bulkLoading !== null}
                className="flex items-center gap-1.5 border border-red-300 text-red-600 hover:bg-red-50 px-3 py-1.5 rounded-lg text-xs font-medium disabled:opacity-50"
              >
                <Trash2 size={12} />
                {bulkLoading === 'delete' ? '삭제 중…' : '일괄 삭제'}
              </button>
            </Tooltip>
            <button
              onClick={() => setSelected(new Set())}
              className="text-gray-400 hover:text-gray-600 text-xs px-2"
            >
              선택 해제
            </button>
          </div>
        </div>
      )}

      {/* Table */}
      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              <th className="px-4 py-3 w-8">
                <input
                  type="checkbox"
                  checked={allSelected}
                  onChange={toggleAll}
                  disabled={tables.length === 0}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
              </th>
              {['테이블명', 'PK', 'Title 컬럼', 'Content 컬럼', '청킹', '상태', ''].map(h => (
                <th key={h} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wide">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {tables.map(t => {
              const cols = contentCols(t)
              const missingCols = cols.length === 0
              const isSelected = selected.has(t.sourceTable)
              const drift = driftMap.get(t.sourceTable)
              return (
                <tr key={t.id} className={`hover:bg-gray-50 ${isSelected ? 'bg-blue-50' : ''}`}>
                  <td className="px-4 py-3">
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => toggleRow(t.sourceTable)}
                      className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                  </td>
                  <td className="px-4 py-3 font-mono text-gray-900 text-xs">
                    <div className="flex items-center gap-1.5">
                      {t.sourceTable}
                      {drift?.status === 'table_missing' && (
                        <span className="text-red-500 text-xs font-medium" title="소스 DB에서 테이블이 삭제됨">⚠ 테이블 없음</span>
                      )}
                      {drift?.status === 'column_mismatch' && (
                        <span className="text-orange-500 text-xs font-medium" title={`삭제된 컬럼: ${drift.missingColumns.join(', ')}`}>⚠ 컬럼 변경</span>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3 font-mono text-gray-500 text-xs">{t.pkColumn}</td>
                  <td className="px-4 py-3 font-mono text-xs">
                    {t.titleColumn
                      ? <span className="text-indigo-600">{t.titleColumn}</span>
                      : <span className="text-gray-300">없음</span>
                    }
                  </td>
                  <td className="px-4 py-3 text-xs">
                    {t.llmStatus === 'pending'
                      ? <span className="inline-flex items-center gap-1 text-blue-500 font-medium animate-pulse">⟳ 분류중</span>
                      : missingCols
                      ? <span className="text-orange-500 font-medium">⚠ 미설정</span>
                      : <span className="text-gray-600">{cols.slice(0, 3).join(', ')}{cols.length > 3 ? ` +${cols.length - 3}` : ''}</span>
                    }
                  </td>
                  <td className="px-4 py-3 text-gray-500 text-xs">
                    {t.llmStatus === 'pending'
                      ? <span className="text-blue-400 animate-pulse">—</span>
                      : `${t.chunkingStrategy} / ${t.chunkSize}`
                    }
                  </td>
                  <td className="px-4 py-3">
                    <Tooltip text={t.isActive ? '클릭하면 RAG 검색 대상에서 제외합니다' : '클릭하면 RAG 검색 대상에 포함합니다'}>
                      <button
                        onClick={() => statusMut.mutate({ sourceTable: t.sourceTable, isActive: !t.isActive })}
                        className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 focus:outline-none ${
                          t.isActive ? 'bg-green-500' : 'bg-gray-300'
                        }`}
                        role="switch"
                        aria-checked={t.isActive}
                      >
                        <span className={`pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow transform transition-transform duration-200 ${
                          t.isActive ? 'translate-x-4' : 'translate-x-0'
                        }`} />
                      </button>
                    </Tooltip>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Tooltip text="title/content/metadata로 쓸 컬럼과 청킹 전략을 지정합니다">
                        <button
                          onClick={() => setConfigTable(t)}
                          className={`flex items-center gap-1 text-xs ${missingCols ? 'text-orange-500 hover:text-orange-700' : 'text-gray-400 hover:text-gray-600'}`}
                        >
                          <Settings size={13} /> 컬럼 설정
                        </button>
                      </Tooltip>
                      <Tooltip text="컬럼 설정을 LLM으로 재분석하고 전체 데이터를 다시 임베딩합니다">
                        <button
                          onClick={() => handleResync(t.sourceTable)}
                          disabled={syncing === t.sourceTable}
                          className="flex items-center gap-1 text-blue-500 hover:text-blue-700 text-xs disabled:opacity-50"
                        >
                          <RefreshCw size={12} className={syncing === t.sourceTable ? 'animate-spin' : ''} />
                          임베딩 자동설정
                        </button>
                      </Tooltip>
                      <Tooltip text="RAG 대상에서 이 테이블을 완전히 제거합니다">
                        <button
                          onClick={() => {
                            if (window.confirm(`${t.sourceTable}을 삭제하시겠습니까?`)) deleteMut.mutate(t.sourceTable)
                          }}
                          className="text-red-400 hover:text-red-600"
                        >
                          <Trash2 size={14} />
                        </button>
                      </Tooltip>
                    </div>
                  </td>
                </tr>
              )
            })}
            {tables.length === 0 && !isLoading && (
              <tr>
                <td colSpan={8} className="px-4 py-8 text-center text-gray-400 text-sm">
                  등록된 테이블이 없습니다. 스키마 탐색으로 일괄 등록하세요.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {showDiscover && (
        <SchemaDiscoverModal
          dsId={dsId}
          mode="rag"
          existingTableNames={tables.map(t => t.sourceTable)}
          onImport={handleBulkImport}
          onClose={() => setShowDiscover(false)}
        />
      )}

      {configTable && (
        <RagColumnConfigModal
          dsId={dsId}
          table={configTable}
          onSaved={() => qc.invalidateQueries({ queryKey: ['admin-rag-tables', dsId] })}
          onClose={() => setConfigTable(null)}
        />
      )}
    </div>
  )
}
