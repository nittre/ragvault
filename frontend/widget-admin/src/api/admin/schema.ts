import apiClient from '../client'

export interface ColumnInfo {
  name: string
  dataType: string
  nullable: boolean
  comment: string
  primaryKey: boolean
}

export interface TableInfo {
  tableName: string
  tableComment: string
  columns: ColumnInfo[]
}

export const getSchema = (dsId: number) =>
  apiClient
    .get<TableInfo[]>('/api/admin/schema/tables', { params: { datasourceId: dsId } })
    .then(r => r.data)
