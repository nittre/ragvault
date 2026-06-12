import React, { useEffect } from 'react'
import { Link } from 'react-router-dom'
import { Plus, X, Settings } from 'lucide-react'
import { useChatStore } from '../../stores/chatStore'
import { useAuthStore } from '../../stores/authStore'

interface ConversationListProps {
  className?: string
}

export default function ConversationList({ className = '' }: ConversationListProps) {
  const { conversations, activeConvId, createConversation, setActiveConv, deleteConversation } =
    useChatStore()
  const role = useAuthStore(s => s.role)
  const isAdmin = role === 'ADMIN' || role === 'SUPER_ADMIN'

  const sorted = [...conversations].sort((a, b) => b.updatedAt - a.updatedAt)

  // 첫 번째 대화 자동 선택
  useEffect(() => {
    if (!activeConvId && sorted.length > 0) {
      setActiveConv(sorted[0].id)
    }
  }, [sorted.length]) // eslint-disable-line react-hooks/exhaustive-deps

  const handleNew = () => {
    const id = createConversation()
    setActiveConv(id)
  }

  const handleDelete = (e: React.MouseEvent, id: string) => {
    e.stopPropagation()
    deleteConversation(id)
  }

  return (
    <aside className={`flex flex-col bg-white border-r border-gray-200 ${className}`}>
      {/* 상단 헤더 */}
      <div className="p-3 border-b border-gray-200">
        <button
          onClick={handleNew}
          className="flex items-center gap-2 w-full bg-blue-600 hover:bg-blue-700 text-white rounded-lg px-3 py-2 text-sm font-medium transition-colors"
        >
          <Plus size={16} />
          새 대화
        </button>
      </div>

      {/* 대화 목록 */}
      <nav className="flex-1 overflow-y-auto p-2 space-y-0.5">
        {sorted.length === 0 && (
          <p className="text-xs text-gray-400 text-center py-4">대화가 없습니다.</p>
        )}
        {sorted.map(conv => (
          <div
            key={conv.id}
            onClick={() => setActiveConv(conv.id)}
            className={`group flex items-center justify-between rounded-lg px-3 py-2 cursor-pointer text-sm transition-colors ${
              activeConvId === conv.id
                ? 'bg-blue-50 text-blue-700 font-medium'
                : 'text-gray-700 hover:bg-gray-100'
            }`}
          >
            <span className="truncate flex-1 mr-1">
              {conv.title.length > 30 ? conv.title.slice(0, 30) + '…' : conv.title}
            </span>
            <button
              onClick={e => handleDelete(e, conv.id)}
              className="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-500 transition-opacity p-0.5 rounded"
              title="대화 삭제"
            >
              <X size={14} />
            </button>
          </div>
        ))}
      </nav>

      {/* 어드민 버튼 — ADMIN/SUPER_ADMIN에게만 노출 */}
      {isAdmin && (
        <div className="p-3 border-t border-gray-200">
          <Link
            to="/admin"
            className="flex items-center gap-2 w-full text-gray-600 hover:bg-gray-100 rounded-lg px-3 py-2 text-sm transition-colors"
          >
            <Settings size={15} className="flex-shrink-0" />
            <span>관리자 설정</span>
          </Link>
        </div>
      )}
    </aside>
  )
}
