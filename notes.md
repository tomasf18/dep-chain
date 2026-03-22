- Gestao de pruing da arvore para valores conflituosos (quando locked, dar prune aos nao locked)
criar testes em que isto acontece (valores conflituosos)

feito:

- linha no verifyQC -> DONE
- 2f+1 - fix code e report -> DONE
- guaranteee that the f+1 received responses are actually identical (usar hash) -> DONE
- precissamos de sequence numbers no nivel da aplicacao para evitar replay attacks (a aplicacao guarda o estado dos clients: "O paulo tem 10 transacoes") -> DONE
- clients precisam de assinar pedidos e replicas de verificar -> DONE
