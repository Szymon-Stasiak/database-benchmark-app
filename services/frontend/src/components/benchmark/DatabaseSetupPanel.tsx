import { useState } from "react"
import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { CustomSelect } from "@/components/ui/custom-select"
import type { DatabaseType, DatabaseTarget, SupportedDatabases } from "@/types/benchmark"
import { DATABASE_TYPE_LABELS } from "@/types/benchmark"

interface DatabaseSetupPanelProps {
  catalog: SupportedDatabases | null
  targets: DatabaseTarget[]
  onAdd: (target: DatabaseTarget) => void
}

export function DatabaseSetupPanel({ catalog, targets, onAdd }: DatabaseSetupPanelProps) {
  const [selectedType, setSelectedType] = useState("")
  const [selectedDb, setSelectedDb] = useState("")
  const [selectedVersion, setSelectedVersion] = useState("")

  const databaseTypes = catalog ? (Object.keys(catalog.types) as DatabaseType[]) : []

  const typeOptions = databaseTypes.map((type) => ({
    value: type,
    label: DATABASE_TYPE_LABELS[type],
  }))

  const databases = selectedType && catalog
    ? catalog.types[selectedType as DatabaseType] || []
    : []

  const dbOptions = databases.map((db) => ({
    value: db.name,
    label: db.displayName,
  }))

  const selectedDbInfo = databases.find((db) => db.name === selectedDb)
  const versionOptions = (selectedDbInfo?.versions || []).map((v) => ({
    value: v,
    label: v,
  }))

  const canAdd =
    selectedType &&
    selectedDb &&
    selectedVersion &&
    targets.length < 5 &&
    !targets.some((t) => t.dbName === selectedDb && t.dbVersion === selectedVersion)

  const handleAdd = () => {
    if (!selectedType || !selectedDb || !selectedVersion) return
    onAdd({
      dbType: selectedType as DatabaseType,
      dbName: selectedDb,
      dbVersion: selectedVersion,
    })
    setSelectedDb("")
    setSelectedVersion("")
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-3 gap-4">
        <div>
          <Label className="mb-2 block">Database Type</Label>
          <CustomSelect
            value={selectedType}
            onChange={(val) => {
              setSelectedType(val)
              setSelectedDb("")
              setSelectedVersion("")
            }}
            options={typeOptions}
            placeholder="Select type..."
          />
        </div>

        <div>
          <Label className="mb-2 block">Database</Label>
          <CustomSelect
            value={selectedDb}
            onChange={(val) => {
              setSelectedDb(val)
              setSelectedVersion("")
            }}
            options={dbOptions}
            placeholder="Select database..."
            disabled={!selectedType}
          />
        </div>

        <div>
          <Label className="mb-2 block">Version</Label>
          <CustomSelect
            value={selectedVersion}
            onChange={setSelectedVersion}
            options={versionOptions}
            placeholder="Select version..."
            disabled={!selectedDb}
          />
        </div>
      </div>

      <Button onClick={handleAdd} disabled={!canAdd} variant="outline" className="w-full">
        {targets.length >= 5 ? "Maximum 5 databases" : "Add Database"}
      </Button>
    </div>
  )
}
