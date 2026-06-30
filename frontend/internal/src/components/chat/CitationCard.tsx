import React, { useState } from 'react'
import { ChevronDown } from 'lucide-react'
import type { CitationSource } from '../../types'

interface CitationCardProps {
  citations: CitationSource[]
}

export default function CitationCard({ citations }: CitationCardProps) {
  const [isOpen, setIsOpen] = useState(false)

  if (!citations || citations.length === 0) return null

  return (
    <div className="mt-2 border border-gray-200 rounded-lg overflow-hidden text-sm">
      <button
        onClick={() => setIsOpen(prev => !prev)}
        className="flex items-center gap-1 w-full px-3 py-2 bg-gray-50 hover:bg-gray-100 text-gray-600 font-medium transition-colors text-left"
      >
        <ChevronDown
          size={14}
          className={`transition-transform ${isOpen ? 'rotate-180' : ''}`}
        />
        출처 {citations.length}개
      </button>

      {isOpen && (
        <ul className="divide-y divide-gray-100">
          {citations.map((c, i) => (
            <li key={`${c.title}-${i}`} className="px-3 py-2 bg-white">
              <div className="flex items-center justify-between gap-2">
                <span className="text-gray-800 text-sm font-medium truncate" title={c.title}>
                  {c.title}
                </span>
                <span className="text-xs text-gray-400 whitespace-nowrap">
                  관련도 {Math.round(c.score * 100)}%
                </span>
              </div>
              <span className="text-xs text-gray-400">{c.source}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
