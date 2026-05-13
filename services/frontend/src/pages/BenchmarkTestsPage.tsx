import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { AppLayout } from "@/components/AppLayout"
import { ArrowLeft, FlaskConical } from "lucide-react"
import { useNavigate } from "react-router-dom"
import { motion } from "framer-motion"

export default function BenchmarkTestsPage() {
  const navigate = useNavigate()

  return (
    <AppLayout>
      <Button variant="ghost" size="sm" onClick={() => navigate(-1)} className="mb-4">
        <ArrowLeft className="h-4 w-4 mr-2" />
        Back
      </Button>

      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35 }}
      >
        <Card>
          <CardContent className="py-16 text-center">
            <FlaskConical className="h-12 w-12 mx-auto text-muted-foreground mb-4" />
            <h2 className="text-xl font-semibold mb-2">Benchmark Tests</h2>
            <p className="text-muted-foreground">
              This feature is coming soon. You will be able to run performance tests
              against your deployed databases.
            </p>
          </CardContent>
        </Card>
      </motion.div>
    </AppLayout>
  )
}
