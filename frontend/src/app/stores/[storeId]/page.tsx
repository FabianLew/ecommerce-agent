import { redirect } from "next/navigation";

import { storePath } from "@/lib/store-path";

export default async function StoreIndex({ params }: { params: Promise<{ storeId: string }> }) {
  redirect(storePath((await params).storeId));
}
