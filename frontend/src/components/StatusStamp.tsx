export function StatusStamp({ status }: { status: string }) {
  const className = `stamp stamp-${status.toLowerCase()}`;
  return <span className={className}>{status.replace("_", " ")}</span>;
}
