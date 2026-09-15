import Link from "next/link";

const sections = [
  ["Fishing spots", "Create and review access, facilities, and location details."],
  ["Species", "Maintain fish profiles, identification notes, and images."],
  ["Regulations", "Publish regional size limits, bag limits, and source updates."],
  ["Data health", "Monitor weather, tide, and rules ingestion jobs."],
];

export default function AdminPage() {
  return (
    <main className="admin-shell">
      <section className="admin-panel">
        <p className="kicker">CatchCheck operations</p>
        <h1>Admin workspace</h1>
        <p>This route is the foundation for authenticated content and data management.</p>
        <div className="admin-grid">
          {sections.map(([title, description]) => <article className="admin-card" key={title}><h2>{title}</h2><p>{description}</p></article>)}
        </div>
        <Link className="back-link" href="/">← Return to website</Link>
      </section>
    </main>
  );
}
