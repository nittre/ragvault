import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, RefreshCw, Trash2 } from 'lucide-react'
import { getSqlTables, createSqlTable, deleteSqlTable, refreshSchemaCache } from '../../api/admin/sqlTables'

export default function SqlTablesPage() {
  const qc = useQueryClient()

  const { data: tables = [], isLoading, error } = useQuery({
    queryKey: ['admin-sql-tables'],
    queryFn: getSqlTables,
  })

  const createMut = useMutation({
    mutationFn: createSqlTable,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-sql-tables'] })
      setShowForm(false)
      setForm({ tableName: '', columnsJson: '' })
    },
  })

  const deleteMut = useMutation({
    mutationFn: deleteSqlTable,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-sql-tables'] }),
  })

  const refreshMut = useMutation({ mutationFn: refreshSchemaCache })

  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({ tableName: '', columnsJson: '' })

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault()
    createMut.mutate(form)
  }

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">SQL 테이블 화이트리스트</h1>
          <p className="text-sm text-gray-500 mt-0.5">Text-to-SQL 조회 허용 테이블을 관리합니다.</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => refreshMut.mutate()}
            disabled={refreshMut.isPending}
            className="flex items-center gap-2 border border-gray-300 hover:bg-gray-50 text-gray-700 px-3 py-2 rounded-lg text-sm font-medium disabled:opacity-50"
          >
            <RefreshCw size={14} className={refreshMut.isPending ? 'animate-spin' : ''} />
            스키마 캐시 갱신
          </button>
          <button
            onClick={() => setShowForm(v => !v)}
            className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium"
          >
            <Plus size={15} /> 테이블 추가
          </button>
        </div>
      </div>

      {showForm && (
        <form
          onSubmit={handleCreate}
          className="mb-6 bg-white border border-gray-200 rounded-xl p-4 flex flex-wrap gap-3 items-end"
        >
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">테이블명</label>
            <input
              required
              value={form.tableName}
              onChange={e => setForm(p => ({ ...p, tableName: e.target.value }))}
              placeholder="schema.table_name"
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-52 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">컬럼 JSON (선택)</label>
            <input
              value={form.columnsJson}
              onChange={e => setForm(p => ({ ...p, columnsJson: e.target.value }))}
              placeholder='{"col": "type"}'
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-64 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <button
            type="submit"
            disabled={createMut.isPending}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium"
          >
            {createMut.isPending ? '추가 중…' : '추가'}
          </button>
          <button
            type="button"
            onClick={() => setShowForm(false)}
            className="text-gray-500 px-3 py-1.5 text-sm"
          >
            취소
          </button>
        </form>
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {['테이블명', '컬럼 정보', '상태', ''].map(h => (
                <th
                  key={h}
                  className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wide"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {tables.map(t => (
              <tr key={t.id} className="hover:bg-gray-50">
                <td className="px-4 py-3 font-mono text-gray-900 text-xs">{t.tableName}</td>
                <td className="px-4 py-3 text-gray-500 text-xs max-w-xs truncate">
                  {t.columnsJson || '-'}
                </td>
                <td className="px-4 py-3">
                  <span
                    className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${
                      t.active ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                    }`}
                  >
                    {t.active ? '활성' : '비활성'}
                  </span>
                </td>
                <td className="px-4 py-3">
                  <button
                    onClick={() => {
                      if (window.confirm(`${t.tableName}을 삭제하시겠습니까?`)) {
                        deleteMut.mutate(t.id)
                      }
                    }}
                    className="text-red-400 hover:text-red-600"
                  >
                    <Trash2 size={14} />
                  </button>
                </td>
              </tr>
            ))}
            {tables.length === 0 && !isLoading && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-gray-400 text-sm">
                  등록된 테이블이 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
