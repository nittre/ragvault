import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { X, ChevronRight, ChevronDown, Search, Database } from 'lucide-react'
import { getSchema, type TableInfo } from '../../api/admin/schema'

interface Props {
  dsId: number
  mode: 'rag' | 'sql'
  existingTableNames: string[]
  onImport: (tableNames: string[]) => Promise<void>
  onClose: () => void
}

export default function SchemaDiscoverModal({ dsId, mode, existingTableNames, onImport, onClose }: Props) {
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [expandedTable, setExpandedTable] = useState<string | null>(null)
  const [importing, setImporting] = useState(false)

  const { data: tables = [], isLoading } = useQuery({
    queryKey: ['admin-schema', dsId],
    queryFn: () => getSchema(dsId),
  })

  const existingSet = new Set(existingTableNames)

  const filtered = tables.filter(t =>
    t.tableName.toLowerCase().includes(search.toLowerCase()) ||
    t.tableComment.toLowerCase().includes(search.toLowerCase()),
  )

  const availableTables = filtered.filter(t => !existingSet.has(t.tableName))

  const toggleSelect = (tableName: string) => {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(tableName)) next.delete(tableName)
      else next.add(tableName)
      return next
    })
  }

  const toggleAll = () => {
    const available = availableTables.map(t => t.tableName)
    const allSelected = available.every(n => selected.has(n))
    if (allSelected) {
      setSelected(prev => {
        const next = new Set(prev)
        available.forEach(n => next.delete(n))
        return next
      })
    } else {
      setSelected(prev => {
        const next = new Set(prev)
        available.forEach(n => next.add(n))
        return next
      })
    }
  }

  const handleImport = async () => {
    if (selected.size === 0) return
    setImporting(true)
    try {
      await onImport(Array.from(selected))
      onClose()
    } finally {
      setImporting(false)
    }
  }

  const modeLabel = mode === 'rag' ? 'RAG' : 'SQL'
  const allAvailableSelected =
    availableTables.length > 0 && availableTables.every(t => selected.has(t.tableName))

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white rounded-xl shadow-2xl w-[760px] max-h-[80vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200">
          <div className="flex items-center gap-2.5">
            <Database size={18} className="text-blue-600" />
            <div>
              <h2 className="text-sm font-semibold text-gray-900">스키마 탐색 — {modeLabel} 테이블 등록</h2>
              <p className="text-xs text-gray-500 mt-0.5">DB에서 발견된 테이블을 선택하여 일괄 등록합니다.</p>
            </div>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <X size={18} />
          </button>
        </div>

        {/* Search */}
        <div className="px-5 py-3 border-b border-gray-100">
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="테이블명 또는 설명 검색…"
              className="w-full pl-9 pr-3 py-1.5 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
        </div>

        {/* Table list */}
        <div className="flex-1 overflow-y-auto">
          {isLoading ? (
            <div className="flex items-center justify-center h-32 text-sm text-gray-400">
              스키마 불러오는 중…
            </div>
          ) : filtered.length === 0 ? (
            <div className="flex items-center justify-center h-32 text-sm text-gray-400">
              테이블이 없습니다.
            </div>
          ) : (
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200 sticky top-0">
                <tr>
                  <th className="px-4 py-2.5 text-left w-8">
                    <input
                      type="checkbox"
                      checked={allAvailableSelected}
                      onChange={toggleAll}
                      disabled={availableTables.length === 0}
                      className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                  </th>
                  <th className="px-3 py-2.5 text-left text-xs font-medium text-gray-500 uppercase">테이블명</th>
                  <th className="px-3 py-2.5 text-left text-xs font-medium text-gray-500 uppercase">설명</th>
                  <th className="px-3 py-2.5 text-left text-xs font-medium text-gray-500 uppercase">컬럼 수</th>
                  <th className="px-3 py-2.5 text-left text-xs font-medium text-gray-500 uppercase">상태</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {filtered.map(t => {
                  const isExisting = existingSet.has(t.tableName)
                  const isExpanded = expandedTable === t.tableName
                  return (
                    <>
                      <tr
                        key={t.tableName}
                        className={`hover:bg-gray-50 ${isExisting ? 'opacity-50' : ''}`}
                      >
                        <td className="px-4 py-2.5">
                          <input
                            type="checkbox"
                            checked={selected.has(t.tableName)}
                            onChange={() => toggleSelect(t.tableName)}
                            disabled={isExisting}
                            className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                          />
                        </td>
                        <td className="px-3 py-2.5">
                          <button
                            onClick={() => setExpandedTable(isExpanded ? null : t.tableName)}
                            className="flex items-center gap-1.5 font-mono text-xs text-gray-900 hover:text-blue-600"
                          >
                            {isExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                            {t.tableName}
                          </button>
                        </td>
                        <td className="px-3 py-2.5 text-xs text-gray-500">{t.tableComment || '-'}</td>
                        <td className="px-3 py-2.5 text-xs text-gray-500">{t.columns.length}</td>
                        <td className="px-3 py-2.5">
                          {isExisting ? (
                            <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-700">
                              등록됨
                            </span>
                          ) : (
                            <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-gray-100 text-gray-500">
                              미등록
                            </span>
                          )}
                        </td>
                      </tr>
                      {isExpanded && (
                        <tr key={`${t.tableName}-cols`}>
                          <td colSpan={5} className="bg-gray-50 px-8 py-2">
                            <ColumnList table={t} />
                          </td>
                        </tr>
                      )}
                    </>
                  )
                })}
              </tbody>
            </table>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-5 py-3.5 border-t border-gray-200 bg-gray-50 rounded-b-xl">
          <span className="text-xs text-gray-500">
            {selected.size > 0
              ? `${selected.size}개 선택됨`
              : `탐색된 테이블 ${tables.length}개 (미등록 ${availableTables.length}개)`}
          </span>
          <div className="flex gap-2">
            <button
              onClick={onClose}
              className="px-4 py-1.5 text-sm text-gray-600 hover:text-gray-800 border border-gray-300 rounded-lg hover:bg-white"
            >
              취소
            </button>
            <button
              onClick={handleImport}
              disabled={selected.size === 0 || importing}
              className="px-4 py-1.5 text-sm font-medium bg-blue-600 hover:bg-blue-700 disabled:opacity-40 text-white rounded-lg"
            >
              {importing ? '등록 중…' : `선택 테이블 ${modeLabel} 등록 (${selected.size}개)`}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

function ColumnList({ table }: { table: TableInfo }) {
  return (
    <div className="flex flex-wrap gap-x-4 gap-y-1 py-1">
      {table.columns.map(col => (
        <span key={col.name} className="flex items-center gap-1 text-xs text-gray-600">
          {col.primaryKey && (
            <span className="text-yellow-500 font-bold" title="Primary Key">PK</span>
          )}
          <span className="font-mono">{col.name}</span>
          <span className="text-gray-400">{col.dataType}</span>
          {col.comment && <span className="text-gray-400">({col.comment})</span>}
        </span>
      ))}
    </div>
  )
}
