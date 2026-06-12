import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Check, X } from 'lucide-react'
import { getSearchConfig, updateSearchConfig } from '../../api/admin/searchConfig'

export default function SearchConfigPage() {
  const qc = useQueryClient()

  const { data: configs = [], isLoading, error } = useQuery({
    queryKey: ['admin-search-config'],
    queryFn: getSearchConfig,
  })

  const updateMut = useMutation({
    mutationFn: ({ key, value }: { key: string; value: string }) =>
      updateSearchConfig(key, { value }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-search-config'] })
      setEditing(null)
    },
  })

  const [editing, setEditing] = useState<string | null>(null)
  const [editValue, setEditValue] = useState('')

  const startEdit = (key: string, currentValue: string) => {
    setEditing(key)
    setEditValue(currentValue)
  }

  const handleSave = (key: string) => {
    updateMut.mutate({ key, value: editValue })
  }

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-xl font-semibold text-gray-900">검색 설정</h1>
        <p className="text-sm text-gray-500 mt-0.5">RAG 검색 동작을 제어하는 글로벌 설정값입니다.</p>
      </div>

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              {['설정 키', '설명', '값', ''].map(h => (
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
            {configs.map(c => (
              <tr key={c.id} className="hover:bg-gray-50">
                <td className="px-4 py-3 font-mono text-gray-900 text-xs w-48">{c.configKey}</td>
                <td className="px-4 py-3 text-gray-500 text-xs max-w-xs">{c.description || '-'}</td>
                <td className="px-4 py-3">
                  {editing === c.configKey ? (
                    <input
                      autoFocus
                      value={editValue}
                      onChange={e => setEditValue(e.target.value)}
                      onKeyDown={e => {
                        if (e.key === 'Enter') handleSave(c.configKey)
                        if (e.key === 'Escape') setEditing(null)
                      }}
                      className="border border-blue-400 rounded px-2 py-1 text-sm w-48 focus:outline-none focus:ring-2 focus:ring-blue-500"
                    />
                  ) : (
                    <span
                      className="font-mono text-gray-900 text-xs cursor-pointer hover:text-blue-600"
                      onClick={() => startEdit(c.configKey, c.configValue)}
                    >
                      {c.configValue}
                    </span>
                  )}
                </td>
                <td className="px-4 py-3">
                  <div className="flex items-center gap-1">
                    {editing === c.configKey ? (
                      <>
                        <button
                          onClick={() => handleSave(c.configKey)}
                          disabled={updateMut.isPending}
                          className="text-green-600 hover:text-green-800 p-1"
                        >
                          <Check size={14} />
                        </button>
                        <button
                          onClick={() => setEditing(null)}
                          className="text-gray-400 hover:text-gray-600 p-1"
                        >
                          <X size={14} />
                        </button>
                      </>
                    ) : (
                      <button
                        onClick={() => startEdit(c.configKey, c.configValue)}
                        className="text-blue-500 hover:text-blue-700 text-xs"
                      >
                        편집
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            {configs.length === 0 && !isLoading && (
              <tr>
                <td colSpan={4} className="px-4 py-8 text-center text-gray-400 text-sm">
                  설정값이 없습니다.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-gray-400 mt-3">
        셀 값을 클릭하거나 '편집'을 누른 후 Enter로 저장, Esc로 취소합니다.
      </p>
    </div>
  )
}
