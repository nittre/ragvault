import apiClient from '../client'

const base = (dsId: number) => `/api/v1/admin/datasources/${dsId}/knowledge`

export type KnowledgeRole = 'rule' | 'measure'

export interface KnowledgeEntry {
  id?: number
  title: string
  knowledgeRole: KnowledgeRole
  content: string
  pinned: boolean
}

export const getKnowledge = (dsId: number) =>
  apiClient.get<KnowledgeEntry[]>(base(dsId)).then(r => r.data)

export const updateKnowledge = (dsId: number, items: KnowledgeEntry[]) =>
  apiClient.put<void>(base(dsId), { items }).then(r => r.data)
